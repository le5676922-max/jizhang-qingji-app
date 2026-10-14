plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.ledger.fixture"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ledger.fixture"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0-test"
    }

    flavorDimensions += "paymentSource"
    productFlavors {
        create("wechat") {
            dimension = "paymentSource"
            applicationId = "com.tencent.mm"
        }
        create("alipay") {
            dimension = "paymentSource"
            applicationId = "com.eg.android.AlipayGphone"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
