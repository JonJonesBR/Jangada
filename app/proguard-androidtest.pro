# Só para o build type "minified" (testes do emulador no APK com R8). Os testes chamam estas classes
# direto (não pelo manifesto), então o R8 as removeria. Tudo o mais continua sujeito ao corte.
-keep class io.github.jonjonesbr.jangada.TerminalService { *; }
-keep class io.github.jonjonesbr.jangada.TerminalService$* { *; }
-keep class io.github.jonjonesbr.jangada.AppSettings { *; }
-keep class io.github.jonjonesbr.jangada.AppSettings$* { *; }
-keep class io.github.jonjonesbr.jangada.Backup { *; }
-keep class io.github.jonjonesbr.jangada.Backup$* { *; }
-keep class io.github.jonjonesbr.jangada.chat.Attachments { *; }
-keep class io.github.jonjonesbr.jangada.chat.Attachments$* { *; }
-keep class io.github.jonjonesbr.jangada.chat.AutoCompact { *; }
-keep class io.github.jonjonesbr.jangada.chat.AutoCompact$* { *; }
-keep class io.github.jonjonesbr.jangada.chat.ChatDrafts { *; }
-keep class io.github.jonjonesbr.jangada.chat.ChatDrafts$* { *; }
-keep class io.github.jonjonesbr.jangada.chat.Markdown { *; }
-keep class io.github.jonjonesbr.jangada.chat.Markdown$* { *; }
-keep class io.github.jonjonesbr.jangada.linux.OpenCodeAuth { *; }
-keep class io.github.jonjonesbr.jangada.linux.OpenCodeAuth$* { *; }
-keep class io.github.jonjonesbr.jangada.linux.LinuxEnv { *; }
-keep class io.github.jonjonesbr.jangada.linux.LinuxEnv$* { *; }
-keep class io.github.jonjonesbr.jangada.linux.Fs { *; }
-keep class io.github.jonjonesbr.jangada.linux.Fs$* { *; }
-keep class io.github.jonjonesbr.jangada.linux.Workspace { *; }
-keep class io.github.jonjonesbr.jangada.linux.Workspace$* { *; }
-keep class io.github.jonjonesbr.jangada.linux.Setup { *; }
-keep class io.github.jonjonesbr.jangada.linux.Setup$* { *; }
# Os testes leem os ids de recursos (R.string.*) e usam as telas direto (inclusive o Companion da base).
-keep class io.github.jonjonesbr.jangada.R { *; }
-keep class io.github.jonjonesbr.jangada.R$* { *; }
-keep class io.github.jonjonesbr.jangada.*Activity { *; }
-keep class io.github.jonjonesbr.jangada.*Activity$* { *; }
# O androidx.test (APK de teste) usa o androidx.tracing e o concurrent-futures do app: o AGP tira a cópia
# duplicada do APK de teste, então o R8 do app não pode podar estas bibliotecas (NoSuchMethodError no início).
-keep class androidx.tracing.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
# O código dos testes (Kotlin) usa o stdlib do app: o AGP tira a cópia duplicada do APK de teste.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
