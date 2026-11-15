# Devil Shield uses no reflection of its own.
# The encryption library refers to desktop-Java classes Android doesn't have; they're never used.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
