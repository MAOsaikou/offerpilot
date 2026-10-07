@echo off
cd /d "D:\develop\GitHub\offerpilot-main\offerpilot-main\backend"
"D:\jdk-17.0.19\bin\javaw.exe" -jar "D:\develop\GitHub\offerpilot-main\offerpilot-main\backend\offerpilot-bootstrap\target\offerpilot-bootstrap-0.1.0-SNAPSHOT.jar" --spring.profiles.active=dev --server.port=8081 --logging.file.name="D:\develop\GitHub\offerpilot-main\offerpilot-main\backend\backend-run.log"
