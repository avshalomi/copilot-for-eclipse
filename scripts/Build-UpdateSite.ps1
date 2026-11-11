[CmdletBinding()]
param(
  [string]$JavaHome = $env:JAVA_HOME,
  [string]$MavenHome,
  [string]$TargetPlatformFile,
  [switch]$UseWindowsCertificateStore,
  [switch]$PackageOnly
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$siteDirectory = Join-Path $repositoryRoot 'com.microsoft.copilot.eclipse.repository/target/repository'
$archivePath = Join-Path $repositoryRoot 'com.microsoft.copilot.eclipse.repository/target/GithubCopilotForEclipse-custom.zip'

if (-not $PackageOnly) {
  $previousJavaHome = $env:JAVA_HOME
  $previousMavenOpts = $env:MAVEN_OPTS
  Push-Location $repositoryRoot
  try {
    if ($JavaHome) {
      if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
        throw "Java executable not found in $JavaHome"
      }
      $env:JAVA_HOME = $JavaHome
    }
    if ($UseWindowsCertificateStore) {
      $env:MAVEN_OPTS = "$previousMavenOpts -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
    }
    $mavenCommand = if ($MavenHome) {
      Join-Path $MavenHome 'bin/mvn.cmd'
    } else {
      Join-Path $repositoryRoot 'mvnw.cmd'
    }
    $mavenArguments = @('clean', 'verify', '--batch-mode')
    if ($TargetPlatformFile) {
      $resolvedTarget = (Resolve-Path -LiteralPath $TargetPlatformFile).Path
      $mavenArguments += "-Dtarget-platform-file=$resolvedTarget"
    }
    & $mavenCommand @mavenArguments
    if ($LASTEXITCODE -ne 0) {
      throw "Maven build failed with exit code $LASTEXITCODE. No installation archive was created."
    }
  } finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:MAVEN_OPTS = $previousMavenOpts
    Pop-Location
  }
}

foreach ($metadataName in @('content', 'artifacts')) {
  $hasMetadata = $false
  foreach ($extension in @('jar', 'xml', 'xml.xz')) {
    if (Test-Path -LiteralPath (Join-Path $siteDirectory "$metadataName.$extension") -PathType Leaf) {
      $hasMetadata = $true
      break
    }
  }
  if (-not $hasMetadata) {
    throw "P2 $metadataName metadata not found in $siteDirectory. Build the repository before packaging."
  }
}
foreach ($directoryName in @('features', 'plugins')) {
  if (-not (Test-Path -LiteralPath (Join-Path $siteDirectory $directoryName) -PathType Container)) {
    throw "P2 $directoryName directory not found in $siteDirectory."
  }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$temporaryArchive = "$archivePath.$([guid]::NewGuid().ToString('N')).tmp"
try {
  [System.IO.Compression.ZipFile]::CreateFromDirectory(
    $siteDirectory, $temporaryArchive, [System.IO.Compression.CompressionLevel]::Optimal, $false)
  Move-Item -LiteralPath $temporaryArchive -Destination $archivePath -Force
} finally {
  if (Test-Path -LiteralPath $temporaryArchive -PathType Leaf) {
    Remove-Item -LiteralPath $temporaryArchive
  }
}

$archive = Get-Item -LiteralPath $archivePath
Write-Output "Eclipse update site: $($archive.FullName)"
Write-Output "Archive size: $([math]::Round($archive.Length / 1MB, 1)) MB"
Write-Output 'Install through Help > Install New Software > Add > Archive, select the ZIP, and restart Eclipse.'
