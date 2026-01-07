# Kilim Plugins 包源码解析

## 概述

`kilim.plugins` 包包含 Kilim 框架的 Maven 插件实现，主要用于在编译阶段对 Java 类文件进行字节码织入（weaving）。该插件允许开发者在 Maven 构建过程中自动处理使用 Kilim 协程的类。

## 核心类：KilimMavenPlugin

### 类定义

```java
@Mojo(name = "weave", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
        requiresDependencyResolution=ResolutionScope.RUNTIME)
public class KilimMavenPlugin extends AbstractMojo
```

`KilimMavenPlugin` 是一个 Maven 插件，继承自 `AbstractMojo`，通过 `@Mojo` 注解定义其为一个名为 "weave" 的 Maven 目标（goal），默认在 `PROCESS_TEST_CLASSES` 阶段执行。

### 主要功能

该插件的主要功能是在 Maven 构建过程中调用 Kilim 的 Weaver（织入器）对编译后的类文件进行字节码转换，将使用 Kilim 协程的类转换为可运行的形式。

### 配置参数

插件提供了以下可配置参数：

1. **args** (String)
   - 默认值: `"-q"`
   - 描述: 传递给织入器的额外参数
   - Maven 属性: `kilim.args`
   - 是否必需: 否

2. **in** (File)
   - 默认值: `${project.build.outputDirectory}`
   - 描述: 主类文件的位置
   - Maven 属性: `kilim.in`
   - 是否必需: 是

3. **tin** (File)
   - 默认值: `${project.build.testOutputDirectory}`
   - 描述: 测试类文件的位置
   - Maven 属性: `kilim.tin`
   - 是否必需: 是

4. **out** (File)
   - 默认值: `${project.build.outputDirectory}`
   - 描述: 织入后主类文件的输出位置
   - Maven 属性: `kilim.out`
   - 是否必需: 是

5. **tout** (File)
   - 默认值: `${project.build.testOutputDirectory}`
   - 描述: 织入后测试类文件的输出位置
   - Maven 属性: `kilim.tout`
   - 是否必需: 是

6. **project** (MavenProject)
   - 默认值: `${project}`
   - 描述: 当前 Maven 项目对象
   - 是否只读: 是
   - 是否必需: 是

### 执行流程

插件的 `execute()` 方法实现了以下执行流程：

1. **获取目录路径**
   ```java
   String indir = in.getAbsolutePath();
   String tindir = tin.getAbsolutePath();
   String outdir = out.getAbsolutePath();
   String toutdir = tout.getAbsolutePath();
   ```

2. **处理主类文件**
   - 获取编译类路径元素
   ```java
   String [] roots = project.getCompileClasspathElements().toArray(new String[0]);
   ```
   - 设置 Weaver 输出目录
   ```java
   Weaver.outputDir = outdir;
   ```
   - 解析并传递参数给 Weaver
   ```java
   if (args != null && args.length() > 0) {
       Weaver.parseArgs(args.split(" "));
   }
   ```
   - 执行织入操作
   ```java
   int err = Weaver.doMain(new String []{ indir }, roots);
   ```
   - 检查织入结果，如果有错误则抛出异常

3. **处理测试类文件**
   - 获取测试类路径元素
   ```java
   String [] troots = project.getTestClasspathElements().toArray(new String[0]);
   ```
   - 设置 Weaver 输出目录为测试输出目录
   ```java
   Weaver.outputDir = toutdir;
   ```
   - 执行织入操作
   ```java
   int err = Weaver.doMain(new String []{ tindir }, troots);
   ```
   - 检查织入结果，如果有错误则抛出异常

4. **异常处理**
   - 捕获所有异常并包装为 `MojoExecutionException` 抛出
   ```java
   catch (Exception e) {
       throw new MojoExecutionException("Error while weaving the classes", e);
   }
   ```

## 使用方式

### 基本配置

在 Maven 项目的 `pom.xml` 中添加插件配置：

```xml
<build>
    <plugins>
        <plugin>
            <groupId>kilim</groupId>
            <artifactId>kilim</artifactId>
            <version>${kilim.version}</version>
            <executions>
                <execution>
                    <goals>
                        <goal>weave</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 自定义配置

可以通过配置参数自定义插件行为：

```xml
<plugin>
    <groupId>kilim</groupId>
    <artifactId>kilim</artifactId>
    <version>${kilim.version}</version>
    <configuration>
        <args>-q -d</args>
        <in>${project.build.outputDirectory}</in>
        <out>${project.build.outputDirectory}</out>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>weave</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### 命令行配置

也可以通过 Maven 命令行传递参数：

```bash
mvn kilim:weave -Dkilim.args="-q -d"
```

## 依赖关系

插件依赖以下核心组件：

1. **kilim.tools.Weaver**
   - Kilim 的核心织入器，负责实际的字节码转换工作
   - 通过 `doMain()` 方法执行织入操作
   - 支持通过 `parseArgs()` 方法解析命令行参数

2. **Maven 插件框架**
   - `org.apache.maven.plugin.AbstractMojo`: Maven 插件基类
   - `org.apache.maven.plugins.annotations`: Maven 插件注解支持

## 设计特点

1. **自动化处理**
   - 插件在 Maven 构建过程中自动执行，无需手动调用
   - 默认在测试类处理阶段执行，确保主类和测试类都被织入

2. **灵活配置**
   - 支持通过多种方式配置参数（POM 文件、命令行）
   - 所有关键路径都可以自定义

3. **错误处理**
   - 对织入过程中的错误进行捕获和转换
   - 提供清晰的错误信息

4. **日志支持**
   - 使用 Maven 的日志系统记录执行过程
   - 支持调试级别日志输出

## 注意事项

1. **执行阶段**
   - 插件默认在 `PROCESS_TEST_CLASSES` 阶段执行
   - 确保在织入前类文件已经编译完成

2. **类路径**
   - 插件需要完整的运行时依赖解析
   - 确保所有必要的依赖都在类路径中

3. **输出目录**
   - 默认情况下，织入后的类文件覆盖原始类文件
   - 可以通过配置修改输出目录以保留原始类文件

4. **参数传递**
   - 参数通过空格分隔传递给 Weaver
   - 确保参数格式符合 Weaver 的要求

## 总结

`KilimMavenPlugin` 是连接 Kilim 框架和 Maven 构建系统的桥梁，它通过自动化的字节码织入过程，使得开发者可以在 Maven 项目中方便地使用 Kilim 协程功能。插件的设计遵循 Maven 插件的最佳实践，提供了灵活的配置选项和完善的错误处理机制，是 Kilim 生态系统的重要组成部分。
