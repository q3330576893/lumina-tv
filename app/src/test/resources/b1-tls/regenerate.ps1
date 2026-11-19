# Public test fixtures only. Never use these private keys or this CA outside loopback tests.
param([string]$Keytool = "$env:JAVA_HOME/bin/keytool.exe")
$ErrorActionPreference = 'Stop'
$fixtureDir = $PSScriptRoot
$scratchDir = Join-Path ([System.IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $scratchDir | Out-Null
function Invoke-Keytool([string[]]$ArgsList) {
    $ErrorActionPreference = 'Continue' # keytool writes successful progress to stderr in Windows PowerShell.
    & $Keytool @ArgsList 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed: $($ArgsList[0])" }
}
$password = 'b1-test-only'
$rootStore = Join-Path $scratchDir 'root.store'
$rootCert = Join-Path $scratchDir 'root.cer'
Invoke-Keytool @('-genkeypair','-alias','root','-dname','CN=B1 LOOPBACK TEST ROOT','-keyalg','RSA','-keysize','2048','-startdate','2024/01/01 00:00:00','-validity','10950','-ext','bc=ca:true','-ext','ku=keyCertSign,cRLSign','-keystore',$rootStore,'-storetype','PKCS12','-storepass',$password)
Invoke-Keytool @('-exportcert','-alias','root','-keystore',$rootStore,'-storepass',$password,'-file',$rootCert)
foreach ($name in @('valid','expired','wrong-host','self-signed')) {
    $target = Join-Path $fixtureDir "$name.store"
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target }
    $dns = if ($name -eq 'wrong-host') { 'wrong.invalid' } else { 'localhost' }
    Invoke-Keytool @('-genkeypair','-alias','leaf','-dname',"CN=$dns",'-keyalg','RSA','-keysize','2048','-startdate','2024/01/01 00:00:00','-validity','10950','-ext',"san=dns:$dns",'-keystore',$target,'-storetype','PKCS12','-storepass',$password)
    if ($name -ne 'self-signed') {
        $csr = Join-Path $scratchDir "$name.csr"
        $cert = Join-Path $scratchDir "$name.cer"
        Invoke-Keytool @('-certreq','-alias','leaf','-keystore',$target,'-storepass',$password,'-file',$csr)
        $start = if ($name -eq 'expired') { '2020/01/01 00:00:00' } else { '2024/01/01 00:00:00' }
        $days = if ($name -eq 'expired') { '1' } else { '10950' }
        Invoke-Keytool @('-gencert','-alias','root','-keystore',$rootStore,'-storepass',$password,'-infile',$csr,'-outfile',$cert,'-startdate',$start,'-validity',$days,'-ext',"san=dns:$dns",'-ext','bc=ca:false','-ext','ku=digitalSignature,keyEncipherment','-ext','eku=serverAuth')
        Invoke-Keytool @('-importcert','-noprompt','-alias','root','-keystore',$target,'-storepass',$password,'-file',$rootCert)
        Invoke-Keytool @('-importcert','-alias','leaf','-keystore',$target,'-storepass',$password,'-file',$cert)
    }
}
Get-ChildItem -LiteralPath $fixtureDir -Filter '*.store' | Select-Object Name,Length
