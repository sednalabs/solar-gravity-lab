@ECHO OFF
SET APP_HOME=%~dp0
SET WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

IF EXIST "%WRAPPER_JAR%" (
  java -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
  EXIT /B %ERRORLEVEL%
)

ECHO gradle-wrapper.jar is not present in this packaged environment.
ECHO Open the project in Android Studio, or regenerate the wrapper with a local Gradle install.
EXIT /B 1
