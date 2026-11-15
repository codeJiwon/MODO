# Pleos Connect SDK: AIDL 바인딩, Gson 리플렉션, 네이티브(JNA) 호출을 사용하므로 전체 보존한다.
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# Pleos universal-access(libuacore.so)가 사용하는 JNA
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# Gson (SDK 내부 모델 직렬화)
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn sun.misc.**

# 그룹 서버와 주고받는 도메인 모델 (kotlinx.serialization)
-keep class com.modo.domain.** { *; }

# Tink(Pleos 보안 모듈 의존성)가 참조하는 컴파일 전용 어노테이션
-dontwarn javax.annotation.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
