package com.lumina.tv.pair

import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import org.json.JSONObject

data class PairConfig(
    val alias: String,
    val note: String,
    val scheme: String,
    val host: String,
    val port: String,
    val path: String,
    val user: String,
    val pass: String,
    val directOnly: Boolean,
    val strmDirect: Boolean,
    val trustAllSsl: Boolean,
)

/**
 * TV 端内置的局域网小服务：
 *  - 电视屏幕显示二维码，手机扫码打开网页，直接填服务器 / 账号 / 密码 → 自动写入电视
 *  - 同一页面切到「遥控器」，手机变成电视遥控（方向键 / 确认 / 返回 / 播放 / 快进）
 */
class PairingServer(
    port: Int,
    private val stateProvider: () -> String,
    private val onConfig: (token: String, server: String, user: String, pass: String) -> Boolean,
    private val onPairRequest: (PairConfig) -> Unit,
    private val authorize: (token: String) -> Boolean,
    private val onRemote: (action: String) -> Unit,
    private val onSearch: (keyword: String) -> Unit,
) : NanoHTTPD(port) {

    private val main = Handler(Looper.getMainLooper())
    private data class PendingRequest(val token: String, val config: PairConfig, val createdAt: Long, var state: String = "pending")
    @Volatile private var pendingRequest: PendingRequest? = null

    private fun mainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/" || uri == "/index.html" -> page()
                uri == "/api/state" && authorized(session) -> newFixedLengthResponse(
                    Response.Status.OK, "application/json; charset=utf-8", stateProvider()
                )
                uri == "/api/config" -> handleConfig(session)
                uri == "/api/pair/request" -> handlePairRequest(session)
                uri == "/api/pair/pending" -> handlePairPending(session)
                uri == "/api/pair/confirm" -> handlePairResolution(session, "confirmed")
                uri == "/api/pair/cancel" -> handlePairResolution(session, "cancelled")
                uri == "/api/remote" && authorized(session) -> handleRemote(session)
                uri == "/api/search" && authorized(session) -> handleSearch(session)
                uri.startsWith("/api/") -> json("{\"ok\":false,\"msg\":\"配对授权无效或已过期\"}")
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error: ${e.message}")
        }
    }

    private fun authorized(session: IHTTPSession): Boolean =
        authorize(session.parameters["token"]?.firstOrNull().orEmpty())

    private fun param(session: IHTTPSession, key: String): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return session.parameters[key]?.firstOrNull() ?: ""
    }

    private fun handleConfig(session: IHTTPSession): Response {
        if (session.method != Method.POST) return json("{\"ok\":false,\"msg\":\"仅支持 POST\"}")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val token = session.parameters["token"]?.firstOrNull().orEmpty()
        val server = session.parameters["server"]?.firstOrNull().orEmpty().trim()
        val user = session.parameters["user"]?.firstOrNull().orEmpty().trim()
        val pass = session.parameters["pass"]?.firstOrNull().orEmpty()
        if (server.isEmpty() || user.isEmpty()) {
            return json("{\"ok\":false,\"msg\":\"服务器地址和用户名不能为空\"}")
        }
        if (token.isBlank()) return json("{\"ok\":false,\"msg\":\"配对二维码无效，请重新扫码\"}")
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val completed = java.util.concurrent.CountDownLatch(1)
        mainThread { try { accepted.set(onConfig(token, server, user, pass)) } finally { completed.countDown() } }
        if (!completed.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json; charset=utf-8", "{\"ok\":false,\"msg\":\"电视暂未准备好接收配置\"}")
        }
        return if (accepted.get()) json("{\"ok\":true,\"msg\":\"已发送到电视，请看电视屏幕\"}")
        else json("{\"ok\":false,\"msg\":\"二维码已失效或已使用，请重新扫码\"}")
    }

    private fun form(session: IHTTPSession): Map<String, String> {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun handlePairRequest(session: IHTTPSession): Response {
        if (session.method != Method.POST) return json(JSONObject().put("ok", false).put("msg", "仅支持 POST").toString())
        val fields = form(session)
        val token = fields["token"].orEmpty()
        if (!authorize(token)) return json(JSONObject().put("ok", false).put("msg", "配对二维码无效或已过期").toString())
        val config = PairConfig(
            alias = fields["alias"].orEmpty().trim(),
            note = fields["note"].orEmpty().trim(),
            scheme = if (fields["scheme"] == "https") "https" else "http",
            host = fields["host"].orEmpty().trim(),
            port = fields["port"].orEmpty().trim().ifBlank { "8096" },
            path = fields["path"].orEmpty().trim().ifBlank { "/" },
            user = fields["user"].orEmpty().trim(),
            pass = fields["pass"].orEmpty(),
            directOnly = fields["directOnly"] == "true",
            strmDirect = fields["strmDirect"] == "true",
            trustAllSsl = fields["trustAllSsl"] == "true",
        )
        if (config.host.isBlank() || config.user.isBlank()) {
            return json(JSONObject().put("ok", false).put("msg", "地址和用户名不能为空").toString())
        }
        synchronized(this) {
            expirePending()
            if (pendingRequest?.state == "pending") return json(JSONObject().put("ok", false).put("msg", "电视上已有待确认请求").toString())
            pendingRequest = PendingRequest(token, config, System.currentTimeMillis())
        }
        mainThread { onPairRequest(config) }
        return json(JSONObject().put("ok", true).put("state", "pending").toString())
    }

    private fun handlePairPending(session: IHTTPSession): Response {
        val token = session.parameters["token"]?.firstOrNull().orEmpty()
        val request = synchronized(this) { expirePending(); pendingRequest?.takeIf { it.token == token } }
        val state = request?.state ?: "expired"
        val msg = when (state) {
            "pending" -> "已发送到电视，请在电视上确认…"
            "confirmed" -> "已同步并登录"
            "cancelled" -> "已在电视上取消"
            else -> "请求已过期"
        }
        return json(JSONObject().put("state", state).put("msg", msg).toString())
    }

    private fun handlePairResolution(session: IHTTPSession, state: String): Response {
        if (session.method != Method.POST) return json(JSONObject().put("ok", false).toString())
        val token = form(session)["token"].orEmpty()
        val resolved = resolvePending(state, token)
        return json(JSONObject().put("ok", resolved).put("state", if (resolved) state else "expired").toString())
    }

    @Synchronized
    private fun expirePending() {
        val request = pendingRequest ?: return
        if (request.state == "pending" && System.currentTimeMillis() - request.createdAt >= 10 * 60 * 1000L) {
            request.state = "expired"
        }
    }

    @Synchronized
    fun resolvePending(state: String, token: String? = null): Boolean {
        expirePending()
        val request = pendingRequest ?: return false
        if (request.state != "pending" || (token != null && request.token != token)) return false
        if (state != "confirmed" && state != "cancelled") return false
        request.state = state
        return true
    }

    @Synchronized
    fun hasPendingRequest(): Boolean {
        expirePending()
        return pendingRequest?.state == "pending"
    }

    private fun handleRemote(session: IHTTPSession): Response {
        val action = param(session, "action")
        if (action.isNotEmpty()) mainThread { onRemote(action) }
        return json("{\"ok\":true}")
    }

    private fun handleSearch(session: IHTTPSession): Response {
        val k = param(session, "keyword").trim()
        if (k.isNotEmpty()) mainThread { onSearch(k) }
        return json("{\"ok\":true}")
    }

    private fun json(body: String) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)

    private fun page(): Response = newFixedLengthResponse(
        Response.Status.OK, "text/html; charset=utf-8", HTML
    )

    companion object {
        const val DEFAULT_PORT = 8971

        fun start(
            stateProvider: () -> String,
            onConfig: (String, String, String, String) -> Boolean,
            onPairRequest: (PairConfig) -> Unit,
            authorize: (String) -> Boolean,
            onRemote: (String) -> Unit,
            onSearch: (String) -> Unit,
        ): PairingServer? {
            var port = DEFAULT_PORT
            while (port < DEFAULT_PORT + 10) {
                try {
                    val s = PairingServer(port, stateProvider, onConfig, onPairRequest, authorize, onRemote, onSearch)
                    s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    return s
                } catch (_: IOException) {
                    port++
                }
            }
            return null
        }

        /** 取本机局域网 IPv4，优先 192.168 / 10 / 172 段。 */
        fun localIp(): String? {
            val candidates = mutableListOf<String>()
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("169.254")) continue
                            candidates += ip
                        }
                    }
                }
            } catch (_: Exception) { }
            return candidates.firstOrNull { it.startsWith("192.168.") }
                ?: candidates.firstOrNull { it.startsWith("10.") }
                ?: candidates.firstOrNull { it.startsWith("172.") }
                ?: candidates.firstOrNull()
        }
    }

    private val HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Lumina TV · 手机配对</title>
<style>
:root{--bg:#070A12;--gl:#6EA8FF;--gl2:#9B8CFF;--txt:#EAF1FF;--sub:#8C9AB4}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;min-height:100vh;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
  background:radial-gradient(120% 80% at 10% 0%,#1B2A5B 0%,transparent 55%),radial-gradient(100% 70% at 90% 10%,#3B2B6B 0%,transparent 50%),var(--bg);
  color:var(--txt);padding:22px 18px 40px}
h1{font-size:20px;font-weight:600;letter-spacing:.5px;margin:6px 0 2px}
.sub{color:var(--sub);font-size:13px;margin-bottom:18px}
.tabs{display:flex;gap:8px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.1);
  padding:5px;border-radius:16px;margin-bottom:18px;backdrop-filter:blur(18px)}
.tab{flex:1;text-align:center;padding:10px;border-radius:12px;font-size:14px;color:var(--sub);transition:.25s}
.tab.on{background:linear-gradient(135deg,rgba(110,168,255,.9),rgba(155,140,255,.9));color:#08101F;font-weight:600}
.card{background:rgba(255,255,255,.055);border:1px solid rgba(255,255,255,.1);border-radius:22px;
  padding:18px;backdrop-filter:blur(24px);box-shadow:0 18px 50px rgba(0,0,0,.45)}
label{display:block;font-size:12px;color:var(--sub);margin:14px 2px 7px;letter-spacing:.4px}
input{width:100%;padding:14px;border-radius:14px;border:1px solid rgba(255,255,255,.12);
  background:rgba(0,0,0,.35);color:var(--txt);font-size:16px;outline:none;transition:.2s}
input:focus{border-color:rgba(110,168,255,.8);box-shadow:0 0 0 3px rgba(110,168,255,.15)}
select{width:100%;padding:14px;border-radius:14px;border:1px solid rgba(255,255,255,.12);background:#111522;color:var(--txt);font-size:16px;outline:none}
.group{margin:18px 0 4px;color:var(--txt);font-size:14px;font-weight:600}
.check{display:flex;align-items:center;gap:10px;margin:14px 2px 0;color:var(--txt);font-size:14px}
.check input{width:20px;height:20px;margin:0;accent-color:#7FB4FF}
.warning{color:#E9B66D;font-size:12px;margin:6px 2px}
button{width:100%;margin-top:20px;padding:15px;border:none;border-radius:16px;font-size:16px;font-weight:600;
  color:#08101F;background:linear-gradient(135deg,#7FB4FF,#A79BFF);transition:transform .15s;box-shadow:0 10px 26px rgba(110,168,255,.28)}
button:active{transform:scale(.97)}
.pad{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:6px}
.key{height:64px;border-radius:18px;display:flex;align-items:center;justify-content:center;font-size:15px;
  background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.1);color:var(--txt);user-select:none;transition:.12s}
.key:active{background:rgba(110,168,255,.35);transform:scale(.95)}
.key.blank{background:transparent;border:none}
.key.ok{background:linear-gradient(135deg,rgba(110,168,255,.85),rgba(155,140,255,.85));color:#08101F;font-weight:700}
.row{display:flex;gap:10px;margin-top:10px}
.row .key{flex:1;height:54px;font-size:14px}
.status{margin-top:16px;font-size:12px;color:var(--sub);text-align:center;min-height:18px}
.toast{position:fixed;left:50%;bottom:34px;transform:translateX(-50%) translateY(20px);opacity:0;
  background:rgba(20,26,40,.92);border:1px solid rgba(255,255,255,.14);padding:12px 20px;border-radius:14px;
  font-size:13px;transition:.3s;pointer-events:none}
.toast.on{opacity:1;transform:translateX(-50%) translateY(0)}
.hide{display:none}
</style>
</head>
<body>
<h1>Lumina TV</h1>
<div class="sub" id="hint">扫码填写配置，确认后在电视上登录</div>

<div class="tabs">
  <div class="tab on" id="t1" onclick="showTab(1)">连接电视</div>
  <div class="tab" id="t2" onclick="showTab(2)">遥控器</div>
</div>

<div class="card" id="pane1">
  <div class="group">服务器</div>
  <label for="alias">别名</label>
  <input id="alias" placeholder="显示在首页的名称" autocapitalize="off">
  <label for="note">备注</label>
  <input id="note" placeholder="可选">
  <label for="scheme">协议</label>
  <select id="scheme"><option value="http">HTTP</option><option value="https">HTTPS</option></select>
  <label for="host">地址</label>
  <input id="host" placeholder="192.168.1.100" autocapitalize="off" autocorrect="off">
  <label for="port">端口</label>
  <input id="port" type="number" inputmode="numeric" value="8096" placeholder="8096">
  <label for="path">路径</label>
  <input id="path" value="/" placeholder="/" autocapitalize="off">
  <div class="group">账号</div>
  <label for="user">用户名</label>
  <input id="user" placeholder="Emby 用户名" autocapitalize="off" autocorrect="off">
  <label for="pass">密码</label>
  <input id="pass" type="password" placeholder="密码（可留空）">
  <div class="group">高级</div>
  <label class="check"><input id="directOnly" type="checkbox">仅直连</label>
  <label class="check"><input id="strmDirect" type="checkbox">STRM 直链</label>
  <label class="check"><input id="trustAllSsl" type="checkbox">信任所有 SSL</label>
  <div class="warning">⚠ 信任所有 SSL 会忽略证书校验，存在安全风险。</div>
  <button onclick="sendConfig()">同步到电视</button>
  <div class="status" id="cfgStatus"></div>
</div>

<div class="card hide" id="pane2">
  <div class="pad">
    <div class="key blank"></div><div class="key" onclick="k('up')">▲</div><div class="key blank"></div>
    <div class="key" onclick="k('left')">◀</div><div class="key ok" onclick="k('ok')">确定</div><div class="key" onclick="k('right')">▶</div>
    <div class="key blank"></div><div class="key" onclick="k('down')">▼</div><div class="key blank"></div>
  </div>
  <div class="row">
    <div class="key" onclick="k('back')">返回</div>
    <div class="key" onclick="k('home')">主页</div>
    <div class="key" onclick="k('menu')">菜单</div>
  </div>
  <div class="row">
    <div class="key" onclick="k('rewind')">« 10s</div>
    <div class="key" onclick="k('playpause')">播放/暂停</div>
    <div class="key" onclick="k('forward')">10s »</div>
  </div>
  <div class="row">
    <div class="key" onclick="k('voldown')">音量 −</div>
    <div class="key" onclick="k('mute')">静音</div>
    <div class="key" onclick="k('volup')">音量 +</div>
  </div>
  <div class="status" id="remoteStatus"></div>
</div>

<div class="toast" id="toast"></div>

<script>
function showTab(n){
  document.getElementById('pane1').className = 'card' + (n===1?'':' hide');
  document.getElementById('pane2').className = 'card' + (n===2?'':' hide');
  document.getElementById('t1').className = 'tab' + (n===1?' on':'');
  document.getElementById('t2').className = 'tab' + (n===2?' on':'');
}
function toast(t){var e=document.getElementById('toast');e.innerText=t;e.classList.add('on');setTimeout(()=>e.classList.remove('on'),1800);}
function post(path,obj,cb){
  obj.token=new URLSearchParams(location.search).get('token')||'';
  var body=Object.keys(obj).map(k=>encodeURIComponent(k)+'='+encodeURIComponent(obj[k])).join('&');
  fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
    .then(r=>r.json()).then(cb).catch(e=>toast('连接失败，请确认手机与电视同一 WiFi'));
}
function sendConfig(){
  var h=document.getElementById('host').value.trim();
  var u=document.getElementById('user').value.trim();
  var p=document.getElementById('pass').value;
  if(!h||!u){toast('请填写地址与用户名');return;}
  document.getElementById('cfgStatus').innerText='正在发送…';
  post('/api/pair/request',{
    alias:document.getElementById('alias').value,
    note:document.getElementById('note').value,
    scheme:document.getElementById('scheme').value,
    host:h,
    port:document.getElementById('port').value||'8096',
    path:document.getElementById('path').value||'/',
    user:u,pass:p,
    directOnly:document.getElementById('directOnly').checked,
    strmDirect:document.getElementById('strmDirect').checked,
    trustAllSsl:document.getElementById('trustAllSsl').checked
  },function(r){
    document.getElementById('cfgStatus').innerText=r.msg||'';
    if(r.ok&&r.state==='pending'){
      document.getElementById('cfgStatus').innerText='已发送到电视，请在电视上确认…';
      pollPairStatus();
      window.pairPoll=setInterval(pollPairStatus,2000);
    }else toast(r.msg||'发送失败');
  });
}
function pollPairStatus(){
  var token=encodeURIComponent(new URLSearchParams(location.search).get('token')||'');
  fetch('/api/pair/pending?token='+token).then(r=>r.json()).then(function(r){
    document.getElementById('cfgStatus').innerText=r.msg||'';
    if(r.state==='confirmed'||r.state==='cancelled'||r.state==='expired')clearInterval(window.pairPoll);
  }).catch(()=>{});
}
function k(a){
  post('/api/remote',{action:a},function(r){
    var s=document.getElementById('remoteStatus');
    s.innerText='已发送：'+a;
    if(navigator.vibrate)navigator.vibrate(8);
  });
}
setInterval(function(){
  fetch('/api/state?token='+encodeURIComponent(new URLSearchParams(location.search).get('token')||'')).then(r=>r.json()).then(function(s){
    if(s&&s.screen)document.getElementById('hint').innerText=s.screen;
    if(s&&s.pairingStatus){
      document.getElementById('cfgStatus').innerText=s.pairingStatus;
      if(s.pairingStatus.indexOf('成功')>=0)document.getElementById('hint').innerText='登录成功，电视已连接';
    }
  }).catch(()=>{});
},2500);
document.getElementById('host').addEventListener('paste',function(e){
  var t=(e.clipboardData||window.clipboardData).getData('text').trim();
  if(/^https?:\/\//i.test(t)){setTimeout(()=>toast('已识别服务器地址'),50);}
});
</script>
</body>
</html>
""".trimIndent()
}
