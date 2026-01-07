# Kilim Tools 包源码解析

## 概述

`kilim.tools` 包提供了 Kilim 框架的核心工具集，包括字节码织入、类文件分析、编译器集成等功能。这些工具是 Kilim 框架实现协程功能的基础设施。

## 包结构

```
kilim.tools
├── Agent.java          // Java Agent 实现
├── Asm.java            // 字节码汇编器
├── DumpClass.java       // 类文件转储工具
├── FlowAnalyzer.java     // 控制流分析工具
├── Javac.java          // Java 编译器封装
├── Kilim.java          // 运行时织入工具
├── P.java              // 打印工具类
└── Weaver.java         // 字节码织入器
```

## 核心类详解

### 1. Agent 类

#### 类定义

```java
public class Agent implements ClassFileTransformer
```

`Agent` 是 Java Agent 实现，用于在类加载时进行字节码转换。

#### 核心功能

**主要用途：**
- 拦截类加载过程
- 收集类文件字节码
- 支持运行时织入

**核心方法：**

1. **transform 方法**
```java
public byte[] transform(
    ClassLoader loader,
    String name,
    Class klass,
    ProtectionDomain protectionDomain,
    byte[] bytes)
    throws IllegalClassFormatException
```
- 处理类加载时的字节码转换
- 特殊处理 MemoryClassLoader 中的类
- 将类字节码存储到 map 中

2. **premain 方法**
```java
public static void premain(String agentArgs, Instrumentation inst)
```
- Agent 入口点
- 注册 ClassFileTransformer
- 初始化存储映射

**使用场景：**
- 运行时字节码织入
- 动态类加载
- 开发环境调试

### 2. Asm 类

#### 类定义

```java
public class Asm
```

`Asm` 是一个字节码汇编器，使用 Jasmin 语法，但支持注解。

#### 主要功能

**特点：**
- 替代 Jasmin 汇编器
- 正确支持注解
- 生成兼容的类文件

**命令行选项：**
```
-d <dir> : 输出目录 (默认: '.')
-f       : 强制重写 (默认: false)
-q       : 静默模式 (默认: verbose)
-nf      : 不计算栈帧 (默认: 计算栈帧)
```

**核心方法：**

1. **parse 方法**
```java
public Asm parse() throws IOException
```
- 解析 .j 汇编文件
- 生成字节码
- 处理错误和异常

2. **parseClass 方法**
```java
private void parseClass()
```
- 解析类声明
- 处理超类和接口
- 调用 parseClassBody()

3. **parseMethod 方法**
```java
private void parseMethod()
```
- 解析方法声明
- 处理异常列表
- 调用 parseMethodBody()

4. **parseInstructions 方法**
```java
private void parseInstructions()
```
- 解析字节码指令
- 支持所有 JVM 指令
- 处理操作数和标签

**使用示例：**
```bash
java kilim.tools.Asm -d ./output MyClass.j
```

### 3. DumpClass 类

#### 类定义

```java
public class DumpClass extends ClassVisitor implements Opcodes
```

`DumpClass` 是类文件转储工具，输出格式兼容 Jasmin。

#### 主要功能

**特点：**
- 等同于 `javap -c -l -private`
- 输出 Jasmin 格式
- 可被 Asm 解析

**核心方法：**

1. **visit 方法**
```java
public void visit(int version, int access, String name, 
               String signature, String superName, String[] interfaces)
```
- 输出类声明
- 处理版本和访问标志
- 输出超类和接口

2. **visitMethod 方法**
```java
public MethodVisitor visitMethod(int access, String name, String desc, 
                           String signature, String[] exceptions)
```
- 输出方法声明
- 处理方法签名
- 返回 DumpMethodVisitor

3. **visitField 方法**
```java
public FieldVisitor visitField(int access, String name, String desc, 
                          String signature, Object value)
```
- 输出字段声明
- 处理字段类型和初始值

**使用示例：**
```bash
java kilim.tools.DumpClass MyClass.class
java kilim.tools.DumpClass myjar.jar
```

### 4. FlowAnalyzer 类

#### 类定义

```java
public class FlowAnalyzer
```

`FlowAnalyzer` 用于分析方法的控制流和变量使用情况。

#### 主要功能

**特点：**
- 分析基本块
- 显示栈和局部变量状态
- 识别变量使用情况

**核心方法：**

1. **analyzeClass 方法**
```java
private static void analyzeClass(String className)
```
- 分析指定类
- 生成 ClassFlow
- 报告所有方法

2. **reportFlow 方法**
```java
private static void reportFlow(MethodFlow method, String className)
```
- 输出方法信息
- 显示基本块
- 报告变量使用

3. **uniqueItems 方法**
```java
private static String uniqueItems(BasicBlock bb, Frame f, Usage u, int nStack)
```
- 计算唯一值
- 生成状态签名
- 统计非常量值

**使用示例：**
```bash
java kilim.tools.FlowAnalyzer MyClass
java kilim.tools.FlowAnalyzer myjar.jar
```

### 5. Javac 类

#### 类定义

```java
public class Javac
```

`Javac` 封装了 Java 编译器 API，支持动态编译。

#### 主要功能

**特点：**
- 动态编译 Java 源码
- 返回类字节码
- 自动处理类路径

**核心方法：**

1. **compile 方法**
```java
public static List<ClassInfo> compile(List<String> srcCodes) throws IOException
```
- 编译源代码列表
- 生成类文件
- 返回字节码列表

2. **getClassPath 方法**
```java
public static ClassPath getClassPath(Class start, ClassLoader end)
```
- 构建类路径
- 从类加载器提取 URL
- 生成命令行格式

3. **getSourceInfo 方法**
```java
private static SourceInfo getSourceInfo(String srcCode)
```
- 解析源代码
- 提取类名
- 生成 SourceInfo

**使用示例：**
```java
List<String> sources = Arrays.asList("public class Foo {}");
List<ClassInfo> classes = Javac.compile(sources);
```

### 6. Kilim 类

#### 类定义

```java
public class Kilim
```

`Kilim` 是运行时织入工具，支持动态加载和织入类。

#### 主要功能

**特点：**
- 运行时字节码织入
- 支持类路径过滤
- 提供 trampoline 机制

**核心方法：**

1. **run 方法**
```java
public static void run(String className, String method, String... args) throws Exception
```
- 使用 WeavingClassLoader 加载类
- 调用指定方法
- 传递参数

2. **trampoline 方法**
```java
public static boolean trampoline(Config config, String... args)
```
- 自动触发织入
- 检查是否已织入
- 在 WeavingClassLoader 上下文中运行

3. **isWoven 方法**
```java
public static boolean isWoven(Class klass)
```
- 检查类是否已织入
- 查找 WOVEN_FIELD
- 返回检查结果

**使用示例：**
```bash
java kilim.tools.Kilim MyClass arg1 arg2
```

或在代码中：
```java
Kilim.trampoline(true, args);
```

### 7. P 类

#### 类定义

```java
public class P
```

`P` 是简单的打印工具类，用于 .j 汇编文件中。

#### 主要方法

```java
public static void pi(int i)           // 打印整数并换行
public static void pn()                // 打印空行
public static void pn(Object o)        // 打印对象并换行
public static void p(Object o)         // 打印对象不换行
public static void ps(Object o)        // 打印对象后跟空格
public static void ptest()            // 打印 "test"
```

**使用示例：**
在 .j 文件中：
```
invokestatic kilim/tools/P/pi(I)V
```

### 8. Weaver 类

#### 类定义

```java
public class Weaver
```

`Weaver` 是 Kilim 的核心字节码织入器，支持命令行和运行时织入。

#### 主要功能

**特点：**
- 支持命令行和运行时织入
- 处理类文件、JAR 文件和目录
- 支持排除模式

**命令行选项：**
```
-d directory : 输出目录 (必需)
-f          : 强制重写
-q          : 静默模式
-x regex    : 排除匹配的类
-c          : 不添加到类路径
```

**核心方法：**

1. **weave 方法**
```java
public List<ClassInfo> weave(List<ClassInfo> classes) throws KilimException, IOException
```
- 织入类列表
- 处理相互递归的类
- 返回修改后的类

2. **weaveFile 方法**
```java
public void weaveFile(String name, InputStream is) throws IOException
```
- 织入单个类文件
- 处理错误和异常
- 写入输出文件

3. **writeClass 方法**
```java
public static void writeClass(ClassInfo ci) throws IOException
```
- 写入类文件
- 创建必要的目录
- 处理特殊类名

**使用示例：**
```bash
java kilim.tools.Weaver -d ./woven ./classes
java kilim.tools.Weaver -d ./woven -x ".*Test.*" ./classes
```

## 工作流程

### 1. 字节码织入流程

```
源代码 → 编译 → 类文件 → 分析 → 织入 → 输出类文件
```

### 2. 运行时织入流程

```
类加载 → Agent 拦截 → 织入 → 加载织入后的类
```

### 3. 工具协作

```
Javac (编译) → Asm/DumpClass (转换) → Weaver (织入) → Kilim (运行)
```

## 设计特点

### 1. 模块化设计

- 每个工具职责单一
- 清晰的接口定义
- 易于扩展和维护

### 2. 灵活性

- 支持多种输入格式
- 可配置的输出选项
- 运行时和编译时织入

### 3. 错误处理

- 完善的异常处理
- 清晰的错误信息
- 优雅的失败处理

### 4. 性能优化

- 缓存机制
- 批量处理
- 智能重写检测

## 使用场景

### 1. 开发环境

```bash
# 编译并织入
javac MyClass.java
java kilim.tools.Weaver -d ./woven MyClass.class

# 运行织入后的类
java -cp ./woven MyClass
```

### 2. 构建集成

```xml
<plugin>
    <groupId>kilim</groupId>
    <artifactId>kilim</artifactId>
    <executions>
        <execution>
            <goals>
                <goal>weave</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### 3. 运行时织入

```java
// 使用 trampoline
public static void main(String[] args) {
    Kilim.trampoline(true, args);
}
```

## 注意事项

1. **类路径管理**
   - 确保所有依赖类在类路径中
   - 注意类加载器的层次结构
   - 避免类冲突

2. **织入顺序**
   - 相互递归的类需要一起织入
   - 注意依赖关系
   - 使用正确的类路径

3. **性能考虑**
   - 批量处理优于单个处理
   - 合理使用排除模式
   - 考虑使用增量织入

4. **调试支持**
   - 使用 -q 选项减少输出
   - 使用 DumpClass 查看字节码
   - 使用 FlowAnalyzer 分析控制流

## 总结

`kilim.tools` 包提供了完整的字节码处理工具链，从编译、分析到织入，支持 Kilim 协程功能的实现。其设计体现了模块化、灵活性和可扩展性的原则，是 Kilim 框架的核心基础设施。通过这些工具，开发者可以在编译时或运行时对类进行字节码转换，实现高效的协程编程模型。
