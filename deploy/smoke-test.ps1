[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost',
    [string]$FilePath = ''
)

$ErrorActionPreference = 'Stop'

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [hashtable]$Form = $null
    )
    $request = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $Headers
        TimeoutSec = 120
    }
    if ($null -ne $Form) {
        $request.Form = $Form
    } elseif ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = $Body | ConvertTo-Json -Depth 10
    }
    try {
        $response = Invoke-RestMethod @request
    } catch {
        $detail = $_.ErrorDetails.Message
        throw "请求失败 $Method $Path：$detail"
    }
    if ($response.code -ne '0') {
        throw "接口返回失败 $Method $Path：$($response.message)"
    }
    return $response.data
}

function New-SampleFile {
    $path = Join-Path ([System.IO.Path]::GetTempPath()) "ai-copilot-smoke-$([Guid]::NewGuid()).md"
    @'
# AI Knowledge Copilot smoke test

发布流程：先在工作空间创建知识库，然后上传文档，等待解析状态变为 COMPLETED，最后使用知识库进行检索和问答。
'@ | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

if (-not $FilePath) {
    $FilePath = New-SampleFile
}
if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
    throw "测试文件不存在：$FilePath"
}

$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$email = "smoke-$suffix@example.com"
$password = "Smoke-$suffix!Aa1"
$username = "smoke-$suffix"

Write-Host "1/7 检查 Backend 健康状态"
Invoke-Api -Method GET -Path '/api/v1/health' | Out-Null

Write-Host "2/7 注册并登录临时账号：$email"
Invoke-Api -Method POST -Path '/api/v1/users' -Body @{ username = $username; email = $email; password = $password } | Out-Null
$login = Invoke-Api -Method POST -Path '/api/v1/users/login' -Body @{ email = $email; password = $password }
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

Write-Host '3/7 创建工作空间和知识库'
$workspace = Invoke-Api -Method POST -Path '/api/v1/workspaces' -Headers $headers -Body @{ name = "Smoke $suffix"; ownerUserId = $login.user.id }
$knowledgeBase = Invoke-Api -Method POST -Path '/api/v1/knowledge-bases' -Headers $headers -Body @{ workspaceId = $workspace.id; name = 'Smoke Knowledge Base'; description = 'Automated end-to-end verification' }

Write-Host "4/7 上传文档：$FilePath"
$document = Invoke-Api -Method POST -Path "/api/v1/knowledge-bases/$($knowledgeBase.id)/documents/upload" -Headers $headers -Form @{ file = Get-Item -LiteralPath $FilePath }

Write-Host '5/7 等待文档解析和向量索引'
$status = $null
for ($attempt = 1; $attempt -le 30; $attempt++) {
    Start-Sleep -Seconds 2
    $status = Invoke-Api -Method GET -Path "/api/v1/knowledge-bases/$($knowledgeBase.id)/documents/$($document.id)/processing-status" -Headers $headers
    Write-Host "  尝试 $attempt/30：$($status.status)"
    if ($status.status -eq 'COMPLETED') { break }
    if ($status.status -eq 'FAILED') { throw "文档解析失败：$($status.processingFailureReason)" }
}
if ($status.status -ne 'COMPLETED') { throw '文档在 60 秒内没有完成解析' }

Write-Host '6/7 检索并生成回答'
$search = Invoke-Api -Method POST -Path "/api/v1/knowledge-bases/$($knowledgeBase.id)/search" -Headers $headers -Body @{ query = '发布流程'; limit = 5 }
if (-not $search -or $search.Count -lt 1) { throw '检索没有返回引用片段' }
$answer = Invoke-Api -Method POST -Path "/api/v1/knowledge-bases/$($knowledgeBase.id)/answer" -Headers $headers -Body @{ question = '发布流程是什么？' }
if ([string]::IsNullOrWhiteSpace($answer.answer)) { throw '回答内容为空' }
if (-not $answer.sources -or $answer.sources.Count -lt 1) { throw '回答没有返回引用来源' }

Write-Host '7/7 端到端验证通过' -ForegroundColor Green
Write-Host "知识库：$($knowledgeBase.name)（#$($knowledgeBase.id)）"
Write-Host "文档：$($document.originalFilename)（#$($document.id)）"
Write-Host "引用数：$($answer.sources.Count)"
Write-Host "回答：$($answer.answer)"
