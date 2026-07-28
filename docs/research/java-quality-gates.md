# 生产 Java 质量门禁工具链

## 结论

只用 **Maven Checkstyle Plugin 3.6.0 + Checkstyle 13.9.0**。Checkstyle 原生提供所需的三个检查，`check` goal 默认在 `verify` 阶段运行且 `failOnViolation=true`，无需再引入 PMD 或 SpotBugs。[Maven goal 文档](https://maven.apache.org/plugins/maven-checkstyle-plugin/check-mojo.html)；[插件版本元数据](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-checkstyle-plugin/maven-metadata.xml)；[Checkstyle 版本元数据](https://repo.maven.apache.org/maven2/com/puppycrawl/tools/checkstyle/maven-metadata.xml)

插件 3.6.0 默认携带旧的 Checkstyle 9.3；官方支持用插件依赖覆盖运行时版本，因此为本项目的 Java 21 源码固定到当前版本 13.9.0。[官方升级说明](https://maven.apache.org/plugins/maven-checkstyle-plugin/examples/upgrading-checkstyle.html)

## 最小可靠配置

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-checkstyle-plugin</artifactId>
  <version>3.6.0</version>
  <dependencies>
    <dependency>
      <groupId>com.puppycrawl.tools</groupId>
      <artifactId>checkstyle</artifactId>
      <version>13.9.0</version>
    </dependency>
  </dependencies>
  <configuration>
    <sourceDirectories>
      <sourceDirectory>${project.build.sourceDirectory}</sourceDirectory>
    </sourceDirectories>
    <includeTestSourceDirectory>false</includeTestSourceDirectory>
    <excludeGeneratedSources>true</excludeGeneratedSources>
    <includeResources>false</includeResources>
    <includeTestResources>false</includeTestResources>
    <checkstyleRules>
      <module name="Checker">
        <module name="TreeWalker">
          <module name="ParameterNumber">
            <property name="max" value="5"/>
          </module>
          <module name="MethodLength">
            <property name="max" value="50"/>
            <property name="tokens" value="METHOD_DEF"/>
          </module>
          <module name="CyclomaticComplexity">
            <property name="max" value="5"/>
          </module>
        </module>
      </module>
    </checkstyleRules>
  </configuration>
  <executions>
    <execution>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

- `ParameterNumber(max=5)` 默认 token 正是 `METHOD_DEF, CTOR_DEF`，覆盖方法和普通构造器。[规则文档](https://checkstyle.org/checks/sizes/parameternumber.html)
- `MethodLength(max=50, tokens=METHOD_DEF)` 只限制方法；默认 `countEmpty=true`，所以注释与空行也计入 50 行。[规则文档](https://checkstyle.org/checks/sizes/methodlength.html)
- `CyclomaticComplexity(max=5)` 按“决策点 + 1”计算，并计入 `if/while/for/catch/case/?:/&&/||` 等；该检查也会检查构造器和初始化块，这是比“方法”略严格但无需额外工具的行为。[规则文档](https://checkstyle.org/checks/metrics/cyclomaticcomplexity.html)

## 排除边界

`sourceDirectories` 显式锁定 `${project.build.sourceDirectory}`（本仓库即 `src/main/java`），而不是插件默认的 `${project.compileSourceRoots}`；后者可能被代码生成插件追加。测试源继续由显式的 `includeTestSourceDirectory=false` 排除，资源也关闭。`excludeGeneratedSources=true` 再排除构建目录下的生成源；这些参数及默认值见 [check goal 参数文档](https://maven.apache.org/plugins/maven-checkstyle-plugin/check-mojo.html)。本仓库 Fabric8 Java 生成器写入 `target/generated-sources/java`，因此不会进入扫描。

无法仅凭 Java 内容可靠识别“生成代码”。若未来把生成文件写进 `src/main/java`，必须把它们迁回 `target/generated-sources/*`；若做不到，再按固定路径加 `<excludes>`。不要按 `@Generated` 做全局抑制，因为它会把同一手写文件中的代码也变成例外。

PMD 的最接近长度规则 `NcssCount` 衡量的是 NCSS（非注释源码语句），不是物理行数，不能精确回答“50 行”；因此第二个插件只会增加配置而不补能力。[PMD 官方规则文档](https://docs.pmd-code.org/latest/pmd_rules_java_design.html#ncsscount)

## 验证

用临时 Java 21 Maven 项目运行 `mvn verify`：`src/main/java` 中 6 参数方法使构建失败；改为 5 参数后构建通过，同时 `src/test/java` 与 `target/generated-sources/java` 中保留同样的 6 参数违规文件。结果分别为退出码 1 和 0。
