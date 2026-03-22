@echo off
set JAVA_HOME=C:\Users\mrliu\.jdks\azul-17.0.12
set PATH=%JAVA_HOME%\bin;%PATH%
echo Using Java: %JAVA_HOME%
java -version
gradlew.bat bootRun
