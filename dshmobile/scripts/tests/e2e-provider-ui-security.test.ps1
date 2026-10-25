param(
  [string]$ScriptPath = (Join-Path $PSScriptRoot '..\e2e-provider-ui.ps1')
)

$ErrorActionPreference = 'Stop'
$tokens = $null
$parseErrors = $null
$resolvedScript = (Resolve-Path $ScriptPath).Path
$ast = [System.Management.Automation.Language.Parser]::ParseFile($resolvedScript, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) { throw 'e2e-provider-ui.ps1 failed PowerShell parsing' }

$parameterText = $ast.ParamBlock.Extent.Text
if ($parameterText -notmatch '\[System\.Security\.SecureString\]\s*\$ApiKey') {
  throw 'ApiKey must be accepted as SecureString, not a plaintext string parameter'
}

$typeTextAst = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'TypeText' }, $true)
$shotAst = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Shot' }, $true)
if ($null -eq $typeTextAst -or $null -eq $shotAst) { throw 'Expected TypeText and Shot functions were not found' }

$unsafeOutput = '(?im)\bWrite-Output\b[^\r\n]*\$(?:ApiKey(?!\s*\.Length)|plainApiKey|ProviderId|DisplayName|BaseUrl|ManualModel|yaml)\b|\bWrite-Output\b[^\r\n]*\$text(?!\s*\.Length)'
if ($ast.Extent.Text -match $unsafeOutput) { throw 'Script contains a direct diagnostic path for a submitted field value or credential' }
if ($ast.Extent.Text -notmatch '(?s)credentialCaptureRestricted\s*=\s*\(\$null\s*-ne\s*\$ApiKey') {
  throw 'Providing a credential must suppress screenshots for the run'
}
if ($ast.Extent.Text -notmatch '(?s)SecureStringToBSTR[\s\S]*?ZeroFreeBSTR') {
  throw 'SecureString conversion must release its unmanaged buffer'
}
if ($ast.Extent.Text -notmatch '(?s)if\s*\(\$DryRun\)\s*\{\s*Write-Output \("\s+\[dry\] type field=api key chars=\{0\} \(redacted\)"[\s\S]*?\}\s*else\s*\{\s*\$plainApiKey\s*=\s*ConvertFrom-SecureApiKey') {
  throw 'DryRun must report only key length and must not materialize the SecureString as plaintext'
}

# Execute only the two extracted helpers in isolation. DryRun prevents ADB calls;
# Shot must take its credential guard before touching ADB or the filesystem.
. ([scriptblock]::Create($typeTextAst.Extent.Text))
. ([scriptblock]::Create($shotAst.Extent.Text))
$DryRun = $true
$Serial = 'test-only'
$script:step = 0
$script:credentialCaptureRestricted = $true
$probe = 'sk-test-not-a-real-key"; $(Write-Output INJECTED);%s`n'
$typeResult = (@(TypeText -text $probe -note 'api key' -Sensitive) -join "`n")
$shotResult = (@(Shot 'form-filled') -join "`n")
if ($typeResult.Contains($probe) -or $typeResult.Contains('INJECTED') -or $typeResult.Contains('sk-test')) {
  throw 'TypeText dry-run output contained a synthetic hostile value'
}
if ($shotResult.Contains($probe) -or $shotResult.Contains('INJECTED') -or $shotResult.Contains('sk-test')) {
  throw 'Shot suppression output contained a synthetic hostile value'
}
if ($shotResult -notmatch 'suppressed') { throw 'Shot did not report that capture was suppressed' }
if ($script:step -ne 0) { throw 'Shot advanced or captured while credential protection was active' }

Write-Output 'PASS: PowerShell parse, secure parameter, output redaction, hostile synthetic value, screenshot suppression'
