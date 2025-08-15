$records = @()
for ($i = 1; $i -le 5000; $i++) {
    $records += "TestRecord_$i,Data_$i,Extra_$i"
}
$records | Out-File "test_5000_records.csv" -Encoding UTF8
Write-Host "Generated 5000 test records in test_5000_records.csv"

