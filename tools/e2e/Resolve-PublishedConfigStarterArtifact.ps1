param(
    [Parameter(Mandatory = $true)]
    [string] $Version,
    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,
    [string] $RepositoryBase = "https://repo.maven.apache.org/maven2"
)

$ErrorActionPreference = "Stop"

if ($Version -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') {
    throw "Invalid praxis-config-starter version '$Version'."
}

$artifactName = "praxis-config-starter-$Version"
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$resolvedOutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path
$publishedJar = Join-Path $resolvedOutputRoot "$artifactName.jar"
$publishedPom = Join-Path $resolvedOutputRoot "$artifactName.pom"
$publishedJarSha512 = Join-Path $resolvedOutputRoot "$artifactName.jar.sha512"
$publishedPomSha512 = Join-Path $resolvedOutputRoot "$artifactName.pom.sha512"
$coordinatePath = "io/github/codexrodrigues/praxis-config-starter/$Version/$artifactName"
$centralBase = "$($RepositoryBase.TrimEnd('/'))/$coordinatePath"

Invoke-WebRequest -Uri "$centralBase.jar" -OutFile $publishedJar -MaximumRetryCount 3 -RetryIntervalSec 5
Invoke-WebRequest -Uri "$centralBase.pom" -OutFile $publishedPom -MaximumRetryCount 3 -RetryIntervalSec 5
Invoke-WebRequest -Uri "$centralBase.jar.sha512" -OutFile $publishedJarSha512 -MaximumRetryCount 3 -RetryIntervalSec 5
Invoke-WebRequest -Uri "$centralBase.pom.sha512" -OutFile $publishedPomSha512 -MaximumRetryCount 3 -RetryIntervalSec 5

function Assert-PublishedSha512([string] $ArtifactPath, [string] $ChecksumPath) {
    $expected = ((Get-Content -LiteralPath $ChecksumPath -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($expected -notmatch '^[0-9a-f]{128}$' -or $actual -ne $expected) {
        throw "Maven Central SHA-512 verification failed for '$ArtifactPath'."
    }
    return $actual
}

$jarSha512 = Assert-PublishedSha512 $publishedJar $publishedJarSha512
$pomSha512 = Assert-PublishedSha512 $publishedPom $publishedPomSha512

[pscustomobject]@{
    groupId = "io.github.codexrodrigues"
    artifactId = "praxis-config-starter"
    version = $Version
    repository = $RepositoryBase
    jarPath = $publishedJar
    pomPath = $publishedPom
    jarSha512 = $jarSha512
    pomSha512 = $pomSha512
}
