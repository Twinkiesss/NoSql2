$ErrorActionPreference = 'Stop'
Write-Host 'CouchDB 1:'
Invoke-RestMethod -Uri 'http://localhost:5984/_up' -Credential (New-Object System.Management.Automation.PSCredential('admin',(ConvertTo-SecureString 'admin' -AsPlainText -Force)))
Write-Host 'CouchDB 2:'
Invoke-RestMethod -Uri 'http://localhost:5985/_up' -Credential (New-Object System.Management.Automation.PSCredential('admin',(ConvertTo-SecureString 'admin' -AsPlainText -Force)))
Write-Host 'Events database:'
Invoke-RestMethod -Uri 'http://localhost:5984/events' -Credential (New-Object System.Management.Automation.PSCredential('admin',(ConvertTo-SecureString 'admin' -AsPlainText -Force)))
Write-Host 'Mango indexes:'
Invoke-RestMethod -Uri 'http://localhost:5984/events/_index' -Credential (New-Object System.Management.Automation.PSCredential('admin',(ConvertTo-SecureString 'admin' -AsPlainText -Force)))
