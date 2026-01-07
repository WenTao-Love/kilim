# Kilim Mirrors包源码详细解析

## 目录
1. [概述](#概述)
2. [核心组件](#核心组件)
3. [类镜像系统](#类镜像系统)
4. [可暂停方法检测](#可暂停方法检测)
5. [类型系统](#类型系统)
6. [学习路径建议](#学习路径建议)

---

## 概述

mirrors包提供了基于ASM的类镜像系统，用于在字节码分析和编织过程中访问类信息。它实现了一个轻量级的反射系统，避免了Java反射的性能开销。

### 主要功能
- 类信息缓存
- 方法信息访问
- 类型系统支持
- 可暂停方法检测
- 类型兼容性检查

### 关键类
- **CachedClassMirrors**：类信息缓存
- **Detector**：可暂停方法检测器
- **ClassMirrorNotFoundException**：类镜像未找到异常
- **ClassMirror**：类镜像（内部类）
- **MethodMirror**：方法镜像（内部类）

---

## 核心组件

### 1. CachedClassMirrors

#### 概述
类信息缓存，提供高效的类信息访问，避免重复加载和解析字节码。

#### 关键属性
- `cachedClasses`：ConcurrentHashMap，缓存类镜像
- `source`：类加载器，用于加载字节码

#### 关键方法
- `classForName(String className)`：通过类名获取类镜像
- `mirror(byte[] bytecode)`：从字节码创建类镜像
- `mirror(Class<?> clazz)`：从类对象创建类镜像
- `getVersion(ClassLoader source, Class klass)`：获取类版本号

#### 设计特点
1. **缓存机制**：使用ConcurrentHashMap缓存类镜像
2. **延迟加载**：只在需要时加载和解析字节码
3. **线程安全**：使用ConcurrentHashMap保证线程安全
4. **避免反射**：使用ASM解析字节码，避免Java反射

#### 内部类：ClassMirror

##### 概述
类镜像，表示一个类的完整信息。

##### 关键属性
- `name`：类名
- `isInterface`：是否为接口
- `declaredMethods`：声明的方法数组
- `interfaceNames`：接口名数组
- `superName`：超类名
- `version`：类版本号

##### 关键方法
- `getName()`：获取类名
- `isInterface()`：判断是否为接口
- `getDeclaredMethods()`：获取声明的方法
- `getInterfaces()`：获取实现的接口
- `getSuperclass()`：获取超类
- `version()`：获取版本号
- `isAssignableFrom(ClassMirror c)`：判断类型兼容性

##### 设计特点
- 使用ASM的ClassVisitor解析字节码
- 只收集必要的信息（类名、方法等）
- 不检查方法体，提高性能
- 支持类型兼容性检查

#### 内部类：MethodMirror

##### 概述
方法镜像，表示一个方法的完整信息。

##### 关键属性
- `name`：方法名
- `desc`：方法描述符
- `modifiers`：方法修饰符
- `exceptions`：异常类型数组
- `isBridge`：是否为桥接方法

##### 关键方法
- `getName()`：获取方法名
- `getMethodDescriptor()`：获取方法描述符
- `getExceptionTypes()`：获取异常类型

##### 设计特点
- 只存储方法签名信息
- 不存储方法体
- 支持桥接方法识别
- 支持异常类型信息

### 2. Detector

#### 概述
可暂停方法检测器，用于判断一个方法是否需要被编织。

#### 关键属性
- `mirrors`：类镜像缓存
- `NOT_PAUSABLE`：NotPausable类的镜像
- `PAUSABLE`：Pausable类的镜像
- `OBJECT`：Object类的镜像

#### 关键常量
- `METHOD_NOT_FOUND_OR_PAUSABLE`：方法未找到或不可暂停
- `PAUSABLE_METHOD_FOUND`：找到可暂停方法
- `METHOD_NOT_PAUSABLE`：找到但不可暂停的方法
- `STANDARD_DONT_CHECK_LIST`：不需要检查的类列表（java.、javax.）

#### 关键方法
- `isPausable(String className, String methodName, String desc)`：判断方法是否可暂停
- `getPausableStatus(String className, String methodName, String desc)`：获取方法的可暂停状态
- `commonSuperType(String oa, String ob)`：查找两个类型的公共超类型
- `classForName(String className)`：通过类名获取类镜像
- `classForNames(String[] classNames)`：通过类名数组获取类镜像数组

#### 检测逻辑

##### 可暂停方法判断
1. **排除标准类**：java.和javax.开头的类不检查
2. **排除构造方法**：<init>和<clinit>方法不检查
3. **检查方法签名**：检查方法是否声明抛出Pausable异常
4. **继承检查**：递归检查父类和接口
5. **桥接方法处理**：跳过桥接方法

##### 检测流程
```
1. 检查是否为标准类（java.、javax.）
   ↓ 是 → 不可暂停
2. 检查是否为构造方法（<init>、<clinit>）
   ↓ 是 → 不可暂停
3. 查找方法
   ↓ 未找到 → 不可暂停
4. 检查方法异常
   ↓ 抛出NotPausable → 不可暂停
5. 检查异常类型
   ↓ 抛出Pausable → 可暂停
```

#### 公共超类型查找

##### 算法
1. 获取两个类型的所有超类列表
2. 从后向前遍历超类列表
3. 找到第一个匹配的超类
4. 如果没有匹配，返回Object

##### 特殊情况
- 接口与接口：返回java/lang/Object
- 相同类型：返回第一个类型
- 无法找到：返回java/lang/Object

### 3. ClassMirrorNotFoundException

#### 概述
类镜像未找到异常，用于表示无法找到类镜像的情况。

#### 关键属性
- `serialVersionUID`：序列化版本号

#### 构造方法
- `ClassMirrorNotFoundException(String msg)`：使用消息构造
- `ClassMirrorNotFoundException(Throwable cause)`：使用原因构造
- `ClassMirrorNotFoundException(String className, ClassNotFoundException e)`：使用类名和原因构造

#### 使用场景
- 类加载失败
- 类未找到
- 字节码解析错误

---

## 类镜像系统

### 1. ASM集成

#### 概述
使用ASM库解析字节码，创建类镜像。

#### 实现方式
- 使用ClassReader读取字节码
- 使用ClassVisitor访问类信息
- 使用MethodVisitor访问方法信息
- 不访问方法体，只收集签名信息

#### 优势
- **性能高**：比Java反射快得多
- **内存占用小**：只存储必要信息
- **不触发类加载**：避免类加载的副作用
- **支持未加载类**：可以分析未加载的类

### 2. 缓存机制

#### 概述
使用ConcurrentHashMap缓存类镜像，避免重复解析。

#### 缓存策略
- 按类名缓存
- 使用putIfAbsent避免重复
- 线程安全的并发访问
- 永久缓存（不清理）

#### 缓存命中
- 第一次访问：解析字节码
- 后续访问：直接返回缓存
- 显著提高性能
- 减少内存分配

### 3. 类型系统

#### 概述
实现了一个轻量级的类型系统，支持类型兼容性检查。

#### 类型表示
- 使用类名表示类型
- 支持基本类型和引用类型
- 支持数组类型
- 支持接口类型

#### 类型操作
- 类型兼容性检查
- 公共超类型查找
- 超类列表获取
- 接口列表获取

---

## 可暂停方法检测

### 1. 检测原理

#### Pausable注解
- 方法声明抛出Pausable异常
- 编译器在字节码中标记
- 运行时可以检测到
- 用于标识需要编织的方法

#### 检测规则
1. **排除标准类**：java.和javax.开头的类不检查
2. **排除构造方法**：<init>和<clinit>方法不检查
3. **检查方法签名**：检查方法是否声明抛出Pausable异常
4. **继承检查**：递归检查父类和接口
5. **桥接方法处理**：跳过桥接方法

### 2. 检测流程

#### 详细流程
```
1. 输入：类名、方法名、方法描述符
   ↓
2. 检查是否为标准类
   ↓ 是 → 返回METHOD_NOT_FOUND_OR_PAUSABLE
   ↓ 否
3. 检查是否为构造方法
   ↓ 是 → 返回METHOD_NOT_FOUND_OR_PAUSABLE
   ↓ 否
4. 查找方法
   ↓ 未找到 → 返回METHOD_NOT_FOUND_OR_PAUSABLE
   ↓ 找到
5. 检查方法异常
   ↓ 无异常 → 返回METHOD_NOT_PAUSABLE
   ↓ 有异常
6. 检查异常类型
   ↓ 抛出NotPausable → 返回METHOD_NOT_PAUSABLE
   ↓ 抛出Pausable → 返回PAUSABLE_METHOD_FOUND
```

### 3. 优化策略

#### 性能优化
- **缓存类镜像**：避免重复解析
- **跳过标准类**：不检查java.和javax.类
- **快速失败**：尽早返回不可暂停状态
- **桥接方法优化**：跳过桥接方法

#### 正确性保证
- **完整继承检查**：递归检查所有父类和接口
- **异常类型检查**：准确判断Pausable异常
- **方法签名匹配**：精确匹配方法描述符
- **公共超类型算法**：正确的类型兼容性检查

---

## 类型系统

### 1. 类型表示

#### 类名格式
- 内部表示：使用斜杠分隔（java/lang/Object）
- 外部表示：使用点分隔（java.lang.Object）
- 描述符格式：使用L开头和;结尾（Ljava/lang/Object;）

#### 转换方法
- `toClassName(String s)`：描述符转类名
- `toDesc(String name)`：类名转描述符
- `map(String word)`：斜杠转点
- `map(String[] words)`：数组转换

### 2. 类型兼容性

#### isAssignableFrom算法
```
1. 检查是否为null
   ↓ 是 → 返回false
   ↓ 否
2. 检查是否相同
   ↓ 是 → 返回true
   ↓ 否
3. 检查超类
   ↓ 可赋值 → 返回true
   ↓ 否
4. 检查接口
   ↓ 可赋值 → 返回true
   ↓ 否
5. 返回false
```

### 3. 公共超类型

#### commonSuperType算法
```
1. 获取两个类型的所有超类列表
   ↓
2. 从后向前遍历超类列表
   ↓
3. 比较超类是否相同
   ↓ 相同 → 返回该超类
   ↓ 不同 → 继续向前
   ↓
4. 如果没有匹配
   ↓
5. 返回java/lang/Object
```

---

## 学习路径建议

### 1. 理解基础组件
- 从CachedClassMirrors开始，了解类镜像缓存
- 学习ClassMirror的类信息表示
- 掌握MethodMirror的方法信息表示
- 理解ClassMirrorNotFoundException的使用

### 2. 学习ASM集成
- 理解ClassVisitor的使用
- 学习MethodVisitor的使用
- 掌握字节码解析流程
- 理解如何避免访问方法体

### 3. 研究可暂停方法检测
- 理解Pausable注解的作用
- 学习检测规则和流程
- 掌握继承检查的实现
- 理解桥接方法的处理

### 4. 学习类型系统
- 理解类型表示方式
- 掌握类型兼容性检查
- 学习公共超类型算法
- 理解类型转换方法

### 5. 实践应用
- 编写简单的类镜像访问
- 实现类型兼容性检查
- 添加自定义的可暂停检测
- 优化类镜像缓存

### 6. 深入理解
- 研究ASM库的详细使用
- 学习字节码格式
- 理解Java类型系统
- 掌握反射优化技巧

通过这样的学习路径，你能够从基础到高级，逐步掌握Kilim的类镜像系统。mirrors包展示了如何使用ASM实现轻量级的反射系统，这些技术对于理解字节码操作和类型系统非常有价值。这个包的设计思想可以应用于其他需要高性能反射访问的场景。
