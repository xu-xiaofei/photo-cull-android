# 照片选片 APP 一键构建脚本（无 Gradle 手工构建链）
# 用法: pwsh build.ps1 [-Install]   （-Install 构建后通过 adb 安装到连接的设备）
# 依赖: JDK17（自动探测 JAVA_HOME 或 jdk_path.txt），其余工具链（aapt2/r8/apksig/android.jar）仓库自带
param([switch]$Install)
$ErrorActionPreference = "Stop"
$dl = $PSScriptRoot
$app = Join-Path $dl "app"
$out = Join-Path $dl "out"
New-Item -ItemType Directory -Force $out | Out-Null

# ===== JDK17 探测：jdk_path.txt > JAVA_HOME =====
$jdk = $null
if (Test-Path (Join-Path $dl "jdk_path.txt")) {
    $p = (Get-Content (Join-Path $dl "jdk_path.txt")).Trim()
    if ($p -and (Test-Path (Join-Path $p "bin\javac.exe"))) { $jdk = $p }
}
if (-not $jdk -and $env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) { $jdk = $env:JAVA_HOME }
if (-not $jdk) { throw "未找到 JDK17：请安装 JDK17 并设置 JAVA_HOME 环境变量（或将 JDK 根目录路径写入本目录 jdk_path.txt 文件），详见 README。" }
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"
Write-Host "JDK: $jdk"

Write-Host "=== 1. aapt2 link（清单→资源包） ==="
& (Join-Path $dl "aapt2.exe") link -o "$out\base.apk" -I (Join-Path $dl "android.jar") --manifest "$app\AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 30 --version-code 1 --version-name 1.0 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "=== 2. javac ==="
New-Item -ItemType Directory -Force "$out\classes" | Out-Null
& javac -encoding UTF-8 --release 8 -classpath (Join-Path $dl "android.jar") -d "$out\classes" (Get-ChildItem "$app\java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "=== 3. d8 dex ==="
$classFiles = (Get-ChildItem "$out\classes" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName })
& java -cp (Join-Path $dl "r8-8.3.37.jar") com.android.tools.r8.D8 --release --lib (Join-Path $dl "android.jar") --output "$out" @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }
if (-not (Test-Path "$out\classes.dex")) { throw "classes.dex 未生成" }

Write-Host "=== 4. 打包 classes.dex 进 APK（.NET Zip，无需 Python） ==="
$unsigned = "$out\unsigned.apk"
Copy-Item "$out\base.apk" $unsigned -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, "Update")
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $zip.Dispose() }
Write-Host "packaged: $unsigned"

Write-Host "=== 5. 生成调试密钥（如无） ==="
$keystore = Join-Path $dl "debug.keystore"
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -keystore $keystore -alias cull -keypass 123456 -storepass 123456 -dname "CN=cull" -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw "keytool 失败" }
}

Write-Host "=== 6. 编译签名器并签名（v1+v2） ==="
$signCode = @'
import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;

public class Sign {
    public static void main(String[] a) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(a[0])) { ks.load(in, a[1].toCharArray()); }
        String alias = ks.aliases().nextElement();
        PrivateKey pk = (PrivateKey) ks.getKey(alias, a[1].toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("cull", pk, Arrays.asList(cert)).build();
        new ApkSigner.Builder(Arrays.asList(sc))
                .setInputApk(new File(a[2]))
                .setOutputApk(new File(a[3]))
                .setMinSdkVersion(26)
                .build()
                .sign();
        System.out.println("signed: " + a[3]);
    }
}
'@
$signCode | Out-File "$out\Sign.java" -Encoding utf8
& javac -encoding UTF-8 -cp (Join-Path $dl "apksig-8.5.0.jar") -d "$out" "$out\Sign.java"
if ($LASTEXITCODE -ne 0) { throw "Sign 编译失败" }
$apk = Join-Path $dl "照片选片.apk"
& java -cp "$(Join-Path $dl 'apksig-8.5.0.jar');$out" Sign $keystore 123456 "$unsigned" $apk
if ($LASTEXITCODE -ne 0) { throw "签名失败" }

Write-Host "=== 完成: $apk ==="
if ($Install) {
    Write-Host "=== 安装到设备 ==="
    adb install -r $apk
}
