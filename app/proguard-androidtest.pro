# Só para o build type "minified" (testes do emulador no APK com R8). Os testes chamam estas classes
# direto (não pelo manifesto), então o R8 as removeria. Tudo o mais continua sujeito ao corte.
-keep class io.github.jonjonesbr.jangada.TerminalService { *; }
-keep class io.github.jonjonesbr.jangada.AppSettings { *; }
-keep class io.github.jonjonesbr.jangada.Backup { *; }
-keep class io.github.jonjonesbr.jangada.linux.LinuxEnv { *; }
-keep class io.github.jonjonesbr.jangada.linux.Fs { *; }
-keep class io.github.jonjonesbr.jangada.linux.Workspace { *; }
-keep class io.github.jonjonesbr.jangada.linux.Setup { *; }
-keep class io.github.jonjonesbr.jangada.linux.Setup$State { *; }
-keep class io.github.jonjonesbr.jangada.linux.Setup$State$* { *; }
# O AGP também passa o R8 pelo APK de teste (mesmas regras); anotações do errorprone citam classes do JDK.
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
