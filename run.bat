@echo off
echo Compiling...
javac -cp ".;pdfbox-app-3.0.7.jar" *.java
echo Running Server...
java -cp ".;pdfbox-app-3.0.7.jar" Server


@REM # Compile with PDFBox in classpath
@REM javac -cp ".;pdfbox-app-3.0.7.jar" *.java

@REM # Run with PDFBox in classpath
@REM java -cp ".;pdfbox-app-3.0.7.jar" Server