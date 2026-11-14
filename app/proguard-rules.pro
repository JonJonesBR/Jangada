# Regras do R8 (buildType release). O R8 remove código e recursos não usados.
# Nomes não são trocados: o repositório é público e o last_crash.txt (JangadaApp)
# precisa mostrar nomes legíveis.
-dontobfuscate

# Mantém número de linha e nome do arquivo nas pilhas de erro (last_crash.txt).
-keepattributes SourceFile,LineNumberTable

# Métodos nativos do libtermux (terminal-emulator). O C localiza a classe JNI e
# chama os métodos por nome; o C só usa FindClass em java/lang/RuntimeException.
-keep class com.termux.terminal.JNI {
    native <methods>;
}
