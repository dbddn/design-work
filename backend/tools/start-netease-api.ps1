$apiRoot = $env:NETEASE_API_ROOT

if ([string]::IsNullOrWhiteSpace($apiRoot)) {
    $apiRoot = "E:\api-enhanced-main"
}

if (-not (Test-Path -LiteralPath $apiRoot)) {
    Write-Error "未找到网易云增强接口目录: $apiRoot"
    exit 1
}

Push-Location $apiRoot
try {
    if (-not (Test-Path -LiteralPath ".\node_modules")) {
        Write-Host "正在安装依赖..."
        npm install
    }

    Write-Host "启动网易云增强接口: $apiRoot"
    node app.js
}
finally {
    Pop-Location
}
