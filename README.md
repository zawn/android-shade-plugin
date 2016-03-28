# android-shade-plugin

[ ![Download](https://api.bintray.com/packages/zhangzhenli/maven/android-shade-plugin/images/download.svg) ](https://bintray.com/zhangzhenli/maven/android-shade-plugin/_latestVersion)

用于合并Gradle Android Library中的依赖至AAR文件的classes.jar.类似于Maven Shade Plugin. 

类似项目:https://github.com/johnrengelman/shadow (该插件暂时不支持Android,2016年1月13日)

## 说明
需配合相应版本的Android Gradle插件使用.
该插件使用了Transform API,参见:http://tools.android.com/tech-docs/new-build-system/transform-api ,要求Android Gradle Plugin插件版本不低于2.0.0


## 使用

1. 引入依赖.

    在build.gradle中添加依赖:
    ``` groovy
    buildscript {
        repositories {
            maven {
                url "https://dl.bintray.com/zhangzhenli/maven/"
            }
            jcenter()
        }
        dependencies {
            classpath 'com.house365.build:android-shade-plugin:2.0.0-beta7'
        }
    }
    
    apply plugin: 'shade'
    ```

2. 在dependencies中配置.

    ``` groovy
    dependencies {
        compile fileTree(dir: 'libs', include: ['*.jar'])
        compile 'com.android.support:appcompat-v7:23.0.0'
        shade 'com.squareup.okhttp:okhttp:2.7.2'
        releaseShade 'com.google.code.gson:gson:2.4'
    }
    ```
   
3. 示例可参考https://github.com/zawn/android-shade-plugin/tree/master/mylibrary
4. 注意在开启混淆的时候libs目录下的jar将在混淆过程中自动打包进混淆后的jar中无需此插件.
