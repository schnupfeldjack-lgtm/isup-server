@echo off
cd /d %~dp0

rem ==========================================
rem  ISUP Server �����ű���Windows��
rem  ������ application-dev.yml ���޸�
rem ==========================================

if not exist "target\isup-server-1.0.0.jar" (
    echo δ�ҵ� target\isup-server-1.0.0.jar
    echo ����ִ��: mvn clean package -DskipTests
    pause
    exit /b 1
)

if not exist "sdk" (
    echo [����] δ�ҵ� sdk Ŀ¼������ SDK δ���ã��豸���޷�ע��
    echo �뽫 libHCISUPCMS.so / HCISUPCMS.dll ���ļ����� sdk\
    echo.
)

echo ���� ISUP Server...
echo �����ļ�: config\application.yml
echo �������: http://127.0.0.1:8080/api/health
java -Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -jar target\isup-server-1.0.0.jar --spring.profiles.active=dev

pause
