$ErrorActionPreference = 'Stop'
docker compose up -d
Write-Host 'Waiting for infrastructure...'
Start-Sleep -Seconds 8
mvn clean package
java -jar target/library-events-service-2.0.0.jar
