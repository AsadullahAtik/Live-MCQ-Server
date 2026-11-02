# Live MCQ — Windows: mvn test + চালু সার্ভারে Student/Teacher/Admin যাত্রা. ফলাফল: প্রজেক্ট ফোল্ডারের report.txt
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root
$report = Join-Path $root 'report.txt'
$testLog = Join-Path $root 'mvn-test.log'
$srvLog = Join-Path $root 'server-run.log'
$out = New-Object System.Collections.ArrayList
function Say($t) { Write-Host $t; [void]$out.Add($t) }
$failed = 0
$base = 'http://localhost:8080'

Say "Live MCQ test report — $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Say "ফোল্ডার: $root"
Say '--- পরিবেশ ---'
try { $j = (& java -version 2>&1 | Out-String).Trim(); Say $j } catch { Say 'java পাওয়া যায়নি (JDK 17+ ইনস্টল করুন)'; $failed++ }
try { $m = (& mvn -v 2>&1 | Select-Object -First 3 | Out-String).Trim(); Say $m } catch { Say 'mvn পাওয়া যায়নি (Maven ইনস্টল করুন)'; $failed++ }

# ---------- ১. mvn test ----------
Say ''
Say '=== ১. mvn test ==='
& mvn -B test *> $testLog
$mvnExit = $LASTEXITCODE
$txt = Get-Content $testLog -Raw -ErrorAction SilentlyContinue
if ($mvnExit -eq 0) { Say 'mvn test: PASS' } else { Say "mvn test: FAIL (exit $mvnExit)"; $failed++ }
Get-Content $testLog -ErrorAction SilentlyContinue | Where-Object { $_ -match 'Tests run:.*Fail|Tests run:.*Time elapsed' } | Select-Object -Last 12 | ForEach-Object { Say $_ }
if ($mvnExit -ne 0) {
  Say '--- ব্যর্থতার অংশ (mvn-test.log থেকে) ---'
  Get-Content $testLog | Where-Object { $_ -match '\[ERROR\]|FAIL|Exception|expected|AssertionError|Caused by' } | Select-Object -First 70 | ForEach-Object { Say $_ }
}

# ---------- ২. সার্ভার চালু ----------
Say ''
Say '=== ২. সার্ভার চালু (মেমরির H2, ডেমো ডেটাসহ) ==='
$busy = $null
try { $busy = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue } catch {}
if ($busy) {
  Say 'পোর্ট 8080 আগে থেকেই ব্যস্ত (আরেকটি সার্ভার চলছে?). সেটি বন্ধ করে আবার চালান।'
  $failed++
} else {
  $env:SPRING_DATASOURCE_URL = 'jdbc:h2:mem:smoke;DB_CLOSE_DELAY=-1'
  $env:APP_SEED_DEMO = 'true'
  $proc = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c mvn -B -q spring-boot:run' -WorkingDirectory $root -PassThru -WindowStyle Hidden -RedirectStandardOutput $srvLog -RedirectStandardError (Join-Path $root 'server-run.err.log')
  $up = $false
  for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    try { $h = Invoke-RestMethod -Uri "$base/api/health" -TimeoutSec 3; if ($h.status -eq 'ok') { $up = $true; break } } catch {}
    if ($proc.HasExited) { break }
  }
  if (-not $up) { Say 'সার্ভার চালু হয়নি (৩ মিনিটের মধ্যে).'; $failed++ } else { Say 'সার্ভার চালু: PASS' }
}

# ---------- সাহায্যকারী ----------
function Call($method, $path, $token, $body) {
  $p = @{ Uri = "$base$path"; Method = $method; UseBasicParsing = $true; TimeoutSec = 20 }
  $h = @{}
  if ($token) { $h['Authorization'] = "Bearer $token" }
  if ($h.Count) { $p['Headers'] = $h }
  if ($null -ne $body) {
    $p['ContentType'] = 'application/json; charset=utf-8'
    $p['Body'] = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 10 -Compress))
  }
  $status = 0; $content = ''
  try {
    $r = Invoke-WebRequest @p
    $status = [int]$r.StatusCode
    $content = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
  } catch {
    $resp = $_.Exception.Response
    if ($resp) {
      $status = [int]$resp.StatusCode
      try { $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8); $content = $sr.ReadToEnd() } catch {}
    } else { $content = $_.Exception.Message }
  }
  $json = $null
  if ($content) { try { $json = $content | ConvertFrom-Json } catch {} }
  return [pscustomobject]@{ Status = $status; Json = $json; Raw = $content }
}
$script:n = 0
function Check($name, $cond, $detail) {
  $script:n++
  if ($cond) { Say ("  PASS  " + $name) } else { Say ("  FAIL  " + $name + "  -> " + $detail); $script:failed++ }
}
function Login($roll, $pw) { $r = Call 'POST' '/api/auth/login' $null @{ roll = $roll; password = $pw }; return $r }

if ($up) {
  Say ''
  Say '=== ৩. Student / Teacher / Admin যাত্রা (আসল HTTP) ==='
  $health = Call 'GET' '/api/health' $null $null
  Check 'health খোলা' ($health.Status -eq 200) $health.Status
  Check 'টোকেন ছাড়া ৪০১' ((Call 'GET' '/api/exams' $null $null).Status -eq 401) 'ব্যতিক্রম'

  $a = Login 'admin' 'admin'; $t = Login 't1' '1234'; $s = Login '101' '1234'; $s2 = Login '201' '1234'
  Check 'Admin লগইন' ($a.Status -eq 200 -and $a.Json.user.role -eq 'ADMIN') $a.Raw
  Check 'Teacher লগইন' ($t.Status -eq 200 -and $t.Json.user.role -eq 'TEACHER') $t.Raw
  Check 'Student লগইন' ($s.Status -eq 200 -and $s.Json.user.role -eq 'STUDENT') $s.Raw
  Check 'ভুল পাসওয়ার্ডে ৪০১' ((Login 'admin' 'wrong').Status -eq 401) 'ব্যতিক্রম'
  $A = $a.Json.token; $T = $t.Json.token; $S = $s.Json.token; $S2 = $s2.Json.token

  # --- Admin ---
  $cls = Call 'GET' '/api/admin/classes' $A $null
  Check 'Admin: শ্রেণির তালিকা' ($cls.Status -eq 200 -and $cls.Json.Count -ge 2) $cls.Raw
  $c9 = $cls.Json[0].id; $c10 = $cls.Json[1].id
  $qs = @(); for ($i = 0; $i -lt 5; $i++) { $qs += @{ text = "Q$i"; options = @("A$i", "B$i", "C$i", "D$i"); correct = (($i * 3) % 4) } }
  $mk = { param($cid, $title) @{ title = $title; classId = $cid; durationMin = 5; perQ = 1; neg = 0.25; shuffle = $true; startAt = $null; endAt = $null; questions = $qs } }
  $e1 = Call 'POST' '/api/admin/exams' $A (& $mk $c9 'smoke-9')
  Check 'Admin: নবম শ্রেণির পরীক্ষা তৈরি' ($e1.Status -eq 200 -and $e1.Json.id -gt 0) $e1.Raw
  $e2 = Call 'POST' '/api/admin/exams' $A (& $mk $c10 'smoke-10')
  Check 'Admin: দশম শ্রেণির পরীক্ষা তৈরি' ($e2.Status -eq 200) $e2.Raw
  $id1 = $e1.Json.id; $id2 = $e2.Json.id
  $bad = Call 'POST' '/api/admin/exams' $A (@{ title = ''; classId = $c9; durationMin = 5; perQ = 1; neg = 0; questions = $qs })
  Check 'Admin: নাম ছাড়া পরীক্ষা ৪০০' ($bad.Status -eq 400) $bad.Status

  # --- Teacher ---
  $tex = Call 'GET' '/api/admin/exams' $T $null
  $ids = @(); foreach ($x in $tex.Json) { $ids += $x.id }
  Check 'Teacher: নিজের শ্রেণির পরীক্ষা দেখে' ($tex.Status -eq 200 -and ($ids -contains $id1)) $tex.Raw
  Check 'Teacher: অন্য শ্রেণির পরীক্ষা দেখে না' (-not ($ids -contains $id2)) 'দশমের পরীক্ষা দেখা যাচ্ছে'
  Check 'Teacher: অন্য শ্রেণির পরীক্ষা খুললে ৪০৩' ((Call 'GET' "/api/admin/exams/$id2" $T $null).Status -eq 403) 'ব্যতিক্রম'
  Check 'Teacher: অন্য শ্রেণিতে পরীক্ষা বানালে ৪০৩' ((Call 'POST' '/api/admin/exams' $T (& $mk $c10 'x')).Status -eq 403) 'ব্যতিক্রম'
  Check 'Teacher: শিক্ষক-তালিকা Admin-এর, ৪০৩' ((Call 'GET' '/api/admin/teachers' $T $null).Status -eq 403) 'ব্যতিক্রম'

  # --- Student ---
  Check 'Student: Admin API-তে ৪০৩' ((Call 'GET' '/api/admin/exams' $S $null).Status -eq 403) 'ব্যতিক্রম'
  Check 'Student: অন্য শ্রেণির পরীক্ষা শুরুতে ৪০৩' ((Call 'POST' "/api/exams/$id2/start" $S $null).Status -eq 403) 'ব্যতিক্রম'
  $list = Call 'GET' '/api/exams' $S $null
  $mine = $null; foreach ($x in $list.Json) { if ($x.id -eq $id1) { $mine = $x } }
  Check 'Student: নিজের পরীক্ষা তালিকায় (open)' ($mine -and $mine.state -eq 'open') ($list.Raw)
  $st = Call 'POST' "/api/exams/$id1/start" $S $null
  Check 'Student: পরীক্ষা শুরু' ($st.Status -eq 200 -and $st.Json.questions.Count -eq 5) $st.Raw
  $att = $st.Json.attemptId
  # shuffle-এর পর্দার ক্রম অনুযায়ী সঠিক উত্তর বের করি
  $ans = @()
  foreach ($q in $st.Json.questions) {
    $n = [int]($q.text.Substring(1)); $want = @('A','B','C','D')[(($n * 3) % 4)] + $n
    $idx = -1; for ($k = 0; $k -lt 4; $k++) { if ($q.options[$k] -eq $want) { $idx = $k } }
    $ans += $idx
  }
  Check 'Student: সঠিক অপশন পর্দায় আছে' (-not ($ans -contains -1)) ($ans -join ',')
  $half = @($ans[0], $ans[1], -1, -1, -1)
  Check 'Student: অটোসেভ' ((Call 'PUT' "/api/attempts/$att/answers" $S @{ answers = $half }).Status -eq 200) 'ব্যতিক্রম'
  $again = Call 'POST' "/api/exams/$id1/start" $S $null
  Check 'Student: আবার শুরুতে একই attempt ও উত্তর ফেরত' ($again.Json.attemptId -eq $att -and $again.Json.answers[0] -eq $ans[0] -and $again.Json.answers[2] -eq -1) $again.Raw
  Check 'Student: জমা' ((Call 'POST' "/api/attempts/$att/submit" $S @{ answers = $ans }).Status -eq 200) 'ব্যতিক্রম'
  $dbl = Call 'POST' "/api/attempts/$att/submit" $S @{ answers = $ans }
  Check 'Student: দ্বিতীয়বার জমা নিরাপদ' ($dbl.Status -eq 200) $dbl.Raw
  Check 'Student: প্রকাশের আগে ফলাফল ৪০৩' ((Call 'GET' "/api/results/$id1" $S $null).Status -eq 403) 'ব্যতিক্রম'
  Check 'Student: জমার পর আবার শুরু ৪০৯' ((Call 'POST' "/api/exams/$id1/start" $S $null).Status -eq 409) 'ব্যতিক্রম'

  # --- Admin: বোর্ড ও প্রকাশ ---
  $board = Call 'GET' "/api/admin/exams/$id1/results" $A $null
  Check 'Admin: লাইভ বোর্ডে ছাত্র দেখা যায়' ($board.Status -eq 200 -and $board.Json.rows.Count -eq 1) $board.Raw
  Check 'Teacher: বোর্ড দেখতে পারে' ((Call 'GET' "/api/admin/exams/$id1/results" $T $null).Status -eq 200) 'ব্যতিক্রম'
  Check 'Admin: প্রকাশ' ((Call 'POST' "/api/admin/exams/$id1/publish" $A $null).Status -eq 200) 'ব্যতিক্রম'
  $res = Call 'GET' "/api/results/$id1" $S $null
  Check 'Student: ফলাফল ৫/৫ সঠিক, র‍্যাংক ১' ($res.Status -eq 200 -and $res.Json.correct -eq 5 -and $res.Json.marks -eq 5 -and $res.Json.rank -eq 1) $res.Raw
  Check 'Student: র‍্যাংক তালিকা' ((Call 'GET' "/api/exams/$id1/leaderboard" $S $null).Status -eq 200) 'ব্যতিক্রম'

  # --- নিবন্ধন ও পাসওয়ার্ড ---
  $roll = 'smoke' + (Get-Random -Maximum 999999)
  $reg = Call 'POST' '/api/auth/register' $null @{ roll = $roll; name = 'Smoke'; password = 'abcd'; classId = $c9 }
  Check 'নিবন্ধন (ছাত্র)' ($reg.Status -eq 200 -and $reg.Json.user.role -eq 'STUDENT') $reg.Raw
  Check 'একই রোলে নিবন্ধন ৪০৯' ((Call 'POST' '/api/auth/register' $null @{ roll = $roll; name = 'Smoke'; password = 'abcd'; classId = $c9 }).Status -eq 409) 'ব্যতিক্রম'
  $RT = $reg.Json.token
  Check 'পাসওয়ার্ড বদল: ভুল পুরনো ৪০০' ((Call 'POST' '/api/me/password' $RT @{ oldPassword = 'zzzz'; newPassword = 'newpass' }).Status -eq 400) 'ব্যতিক্রম'
  Check 'পাসওয়ার্ড বদল' ((Call 'POST' '/api/me/password' $RT @{ oldPassword = 'abcd'; newPassword = 'newpass' }).Status -eq 200) 'ব্যতিক্রম'
  Check 'নতুন পাসওয়ার্ডে লগইন' ((Login $roll 'newpass').Status -eq 200 -and (Login $roll 'abcd').Status -eq 401) 'ব্যতিক্রম'
  Check 'Student অন্যের পাসওয়ার্ড বদলাতে পারে না' ((Call 'PUT' "/api/admin/users/$($reg.Json.user.id)/password" $RT @{ newPassword = 'hack12' }).Status -eq 403) 'ব্যতিক্রম'
  Check 'Admin পাসওয়ার্ড রিসেট' ((Call 'PUT' "/api/admin/users/$($reg.Json.user.id)/password" $A @{ newPassword = 'reset99' }).Status -eq 200 -and (Login $roll 'reset99').Status -eq 200) 'ব্যতিক্রম'

  # --- Admin: শিক্ষক, শ্রেণি ---
  $tr = 'tz' + (Get-Random -Maximum 999999)
  $nt = Call 'POST' '/api/admin/teachers' $A @{ roll = $tr; name = 'New Teacher'; password = 'abcd'; classIds = @($c10) }
  Check 'Admin: নতুন শিক্ষক' ($nt.Status -eq 200) $nt.Raw
  $ntl = Login $tr 'abcd'
  $ntc = Call 'GET' '/api/admin/classes' $ntl.Json.token $null
  Check 'নতুন শিক্ষক শুধু দশম দেখে' ($ntc.Status -eq 200 -and $ntc.Json.Count -eq 1 -and $ntc.Json[0].id -eq $c10) $ntc.Raw
  Check 'Admin: শিক্ষক মুছা' ((Call 'DELETE' "/api/admin/teachers/$($nt.Json.id)" $A $null).Status -eq 200) 'ব্যতিক্রম'
  Check 'Admin: পরীক্ষা মুছা' ((Call 'DELETE' "/api/admin/exams/$id2" $A $null).Status -eq 200 -and (Call 'GET' "/api/admin/exams/$id2" $A $null).Status -eq 404) 'ব্যতিক্রম'
}

# ---------- সার্ভার বন্ধ ----------
if ($up -or $proc) {
  try { Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue } } catch {}
  try { if ($proc) { & taskkill /PID $proc.Id /T /F 2>&1 | Out-Null } } catch {}
}

Say ''
Say '=== ফলাফল ==='
if ($failed -eq 0) { Say "সব ঠিক আছে (API যাচাই: $script:n টি)." } else { Say "$failed টি সমস্যা পাওয়া গেছে." }
if ($failed -gt 0 -and (Test-Path $srvLog)) {
  Say ''
  Say '--- server-run.log (শেষ ৬০ লাইন) ---'
  Get-Content $srvLog -Tail 60 | ForEach-Object { Say $_ }
  $errLog = Join-Path $root 'server-run.err.log'
  if (Test-Path $errLog) { Say '--- server-run.err.log (শেষ ৪০ লাইন) ---'; Get-Content $errLog -Tail 40 | ForEach-Object { Say $_ } }
}
$out | Out-File -FilePath $report -Encoding utf8
Write-Host "রিপোর্ট লেখা হয়েছে: $report"
