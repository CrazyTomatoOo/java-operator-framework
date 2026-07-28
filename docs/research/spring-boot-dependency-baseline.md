# Spring Boot 依赖版本基线

_结论日期：2026-07-28_

## 结论

建议把以下版本组合作为专用 Operator 框架的**最低稳定基线**：

| 组件 | 基线版本 | 依据 |
|---|---:|---|
| Spring Boot / `spring-boot-dependencies` / `spring-boot-maven-plugin` | **3.5.16** | 官方 3.5 文档当前对应 3.5.16；该版本要求 Java 17+、Spring Framework 6.2.19+ 和 Maven 3.6.3+。[System Requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) |
| Spring Framework | **6.2.19** | Boot 3.5.16 的官方版本元数据固定为 6.2.19；应由 Boot BOM 管理，不单独覆盖。[gradle.properties](https://github.com/spring-projects/spring-boot/blob/v3.5.16/gradle.properties#L22) |
| Java | **21 LTS** | Boot 的最低运行要求虽为 17，但仓库所有模块已统一编译到 21；Java 21 是 LTS，Oracle Premier Support 至 2028-09、Extended Support 至 2031-09。[Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) · [Oracle roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) · [当前 POM](../../operator/framework/pom.xml) |
| Fabric8 Kubernetes Client（含 BOM、CRD/Java generator 插件） | **7.8.0** | 7.8.0 是正式发布版；其源码 POM 使用 Java 11 基线及 Jackson 2.21.4，因此可运行于 Java 21，并与 Boot 3.5.16 的 Jackson 2.21.4 精确对齐。[7.8.0 release](https://github.com/fabric8io/kubernetes-client/releases/tag/v7.8.0) · [Fabric8 POM](https://github.com/fabric8io/kubernetes-client/blob/v7.8.0/pom.xml#L26-L26) · [Java/Jackson properties](https://github.com/fabric8io/kubernetes-client/blob/v7.8.0/pom.xml#L93-L93) · [v7 migration guide](https://github.com/fabric8io/kubernetes-client/blob/v7.8.0/doc/MIGRATION-v7.md#java-11) |
| Micrometer | **1.15.12** | Boot 3.5.16 BOM 管理该版本；不要沿用仓库当前手工指定的 1.16.1。[Boot dependency metadata](https://github.com/spring-projects/spring-boot/blob/v3.5.16/spring-boot-project/spring-boot-dependencies/build.gradle#L1592-L1592) |
| Maven Compiler / Source / Surefire Plugin | **3.14.1 / 3.3.1 / 3.5.6** | 采用 Boot 3.5.16 BOM 管理的插件版本，而非再建一套独立版本矩阵。[Boot dependency metadata](https://github.com/spring-projects/spring-boot/blob/v3.5.16/spring-boot-project/spring-boot-dependencies/build.gradle#L1421-L1421) · [Source](https://github.com/spring-projects/spring-boot/blob/v3.5.16/spring-boot-project/spring-boot-dependencies/build.gradle#L1558-L1558) · [Surefire](https://github.com/spring-projects/spring-boot/blob/v3.5.16/spring-boot-project/spring-boot-dependencies/build.gradle#L1572-L1572) |

## 约束与理由

1. **以 BOM 为唯一版本权威。** Spring 官方建议使用能消费依赖管理的构建系统，并明确建议不要自行指定 Spring Framework 版本；导入 `spring-boot-dependencies:3.5.16`，再导入 Fabric8 的 `kubernetes-client-bom:7.8.0`，仅对 Boot 未管理的 Fabric8 坐标补齐版本。[Spring build systems](https://docs.spring.io/spring-boot/3.5/reference/using/build-systems.html)
2. **暂不选 Spring Boot 4。** Boot 4 改为模块化设计，并以 Jackson 3 为首选；Fabric8 7.8.0 仍基于 Jackson 2。Boot 4 虽可并存 Jackson 2，但官方把兼容模块定位为迁移用、且将来删除，因此不是“最低且稳定”的组合。[Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide#module-dependencies) · [Jackson migration](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide#upgrading-jackson)
3. **跟随同一维护线的补丁版本。** Spring Boot 官方保证主版本至少支持 3 年、次版本至少 12 个月，并建议升级到最新受支持补丁；补丁版原则上向后兼容。[Supported Versions](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions)

## 仓库现状与验证

当前 `operator/framework/pom.xml` 已采用 Java 21，但固定 Fabric8 7.3.0、Micrometer 1.16.1、Compiler Plugin 3.13.0 和 Source Plugin 3.3.1，尚未使用 Spring Boot BOM。[当前 POM](../../operator/framework/pom.xml)

用临时 Maven POM 同时导入 Boot 3.5.16 与 Fabric8 7.8.0 BOM 后，`mvn dependency:tree` 成功解析为 Spring Framework **6.2.19**、Fabric8 **7.8.0**、Micrometer **1.15.12**、Jackson Databind **2.21.4**，无版本分叉。此票仅形成决策，不修改框架代码或 POM。
