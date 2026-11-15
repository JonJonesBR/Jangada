# Regras do R8 do próprio APK de teste (o AGP o minifica quando o build type testado é o "minified").
# As anotações do errorprone citam classes do JDK que não existem no Android.
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
