# Kilim Analysis包源码详细解析

## 目录
1. [概述](#概述)
2. [基础组件](#基础组件)
3. [核心分析组件](#核心分析组件)
4. [数据流分析](#数据流分析)
5. [字节码编织](#字节码编织)
6. [学习路径建议](#学习路径建议)

---

## 概述

analysis包是Kilim的核心包，负责字节码分析、数据流分析和字节码编织。它使用ASM库来分析和修改Java字节码，以支持可暂停的方法（pausable methods）。

### 主要功能
- 字节码解析和验证
- 控制流分析
- 数据流分析
- 字节码编织
- 类型系统管理

### 关键类
- **KilimContext**：编织上下文
- **ClassFlow**：类级别的字节码分析
- **MethodFlow**：方法级别的字节码分析
- **BasicBlock**：基本块表示
- **Frame**：激活帧表示
- **Value**：SSA值表示
- **Usage**：局部变量使用分析
- **TypeDesc**：类型描述符工具
- **ClassWeaver**：类级别的字节码编织
- **MethodWeaver**：方法级别的字节码编织
- **CallWeaver**：方法调用编织

---

## 基础组件

### 1. KilimContext

#### 概述
编织上下文，提供字节码分析所需的共享资源。

#### 关键属性
- `detector`：类型检测器，用于检测类和方法信息

#### 使用场景
- 在多个编织组件之间共享资源
- 提供类型检测能力
- 管理编织上下文

### 2. ClassInfo

#### 概述
表示类信息，包含类名和字节码。

#### 关键属性
- `className`：完全限定类名
- `bytes`：类的字节码

#### 使用场景
- 存储编织后的类信息
- 在类加载器中使用
- 管理类的元数据

### 3. TypeDesc

#### 概述
类型描述符工具类，提供类型操作和合并功能。

#### 关键方法
- `getInterned(String desc)`：获取内部化的类型描述符
- `getReturnTypeDesc(String desc)`：获取返回类型描述符
- `getArgumentTypes(String methodDescriptor)`：获取参数类型数组
- `mergeType(Detector det, String a, String b)`：合并两个类型
- `isDoubleWord(String desc)`：判断是否为双字类型
- `isSingleWord(String desc)`：判断是否为单字类型
- `isRefType(String typeDesc)`：判断是否为引用类型
- `isIntType(String typeDesc)`：判断是否为整型类型

#### 使用场景
- 类型系统管理
- 类型合并和转换
- 方法签名解析
- 类型兼容性检查

### 4. Value

#### 概述
SSA（静态单赋值）值表示，用于数据流分析。

#### 关键属性
- `typeDesc`：类型描述符
- `constVal`：常量值
- `numSites`：定义点数量
- `sites`：定义点数组

#### 关键方法
- `make(int pos, String desc)`：创建新值
- `merge(Detector det, Value other)`：合并两个值
- `isConstant()`：判断是否为常量
- `category()`：获取值类别（1或2）

#### 使用场景
- 数据流分析
- 类型推断
- 常量传播
- 活性分析

### 5. Usage

#### 概述
局部变量使用分析，跟踪局部变量的使用情况。

#### 关键属性
- `nLocals`：局部变量数量
- `in`：输入变量集合
- `born`：已定义变量集合
- `use`：使用变量集合
- `def`：定义变量集合

#### 关键方法
- `read(int var)`：记录变量读取
- `write(int var)`：记录变量写入
- `born(int var)`：记录变量定义
- `isLiveIn(int var)`：判断变量是否活跃
- `evalLiveIn(ArrayList<Usage> succUsage, ArrayList<Handler> handUsage)`：计算活跃变量

#### 使用场景
- 活性分析
- 变量生命周期管理
- 优化状态保存
- 异常处理分析

### 6. Frame

#### 概述
激活帧表示，包含局部变量和操作数栈。

#### 关键属性
- `locals`：局部变量数组
- `stack`：操作数栈
- `stacklen`：栈长度
- `numMonitorsActive`：活动监视器数量

#### 关键方法
- `merge(Detector det, Frame inframe, boolean localsOnly, Usage usage)`：合并帧
- `setLocal(int local, Value v)`：设置局部变量
- `getLocal(int local, int opcode)`：获取局部变量
- `push(Value v)`：压栈
- `pop()`：弹栈
- `dup()`：复制帧

#### 使用场景
- 数据流分析
- 类型检查
- 栈管理
- 帧合并

---

## 核心分析组件

### 1. BasicBlock

#### 概述
基本块表示，包含连续的指令序列。

#### 关键特性
- 以标签开始，以控制转移指令结束
- 不允许跳转到基本块中间
- 处理异常处理块
- 处理JSR/RET指令

#### 关键方法
- `analyze()`：分析基本块
- `verifyPausables()`：验证可暂停方法
- `collectLiveVars()`：收集活跃变量

#### 使用场景
- 控制流分析
- 基本块划分
- 异常处理
- 子程序内联

### 2. ClassFlow

#### 概述
类级别的字节码分析，管理所有方法的分析。

#### 关键属性
- `methodFlows`：方法流列表
- `cr`：类读取器
- `classDesc`：类描述符
- `isPausable`：是否包含可暂停方法
- `isWoven`：是否已编织
- `code`：原始字节码

#### 关键方法
- `analyze(boolean forceAnalysis)`：分析类
- `getSAM()`：获取单抽象方法
- `isPausable()`：判断是否包含可暂停方法
- `getClassName()`：获取类名

#### 使用场景
- 类级别的字节码分析
- 方法级别的分析协调
- SAM（单抽象方法）检测
- 编织决策

### 3. MethodFlow

#### 概述
方法级别的字节码分析，管理方法的基本块和数据流。

#### 关键属性
- `classFlow`：所属类流
- `posToLabelMap`：位置到标签映射
- `labelToBBMap`：标签到基本块映射
- `basicBlocks`：基本块列表
- `pausableMethods`：可暂停方法列表
- `detector`：类型检测器

#### 关键方法
- `analyze()`：分析方法
- `verifyPausables()`：验证可暂停方法
- `isPausable()`：判断是否为可暂停方法
- `needsWeaving()`：判断是否需要编织

#### 使用场景
- 方法级别的字节码分析
- 基本块构建
- 数据流分析
- 可暂停方法检测

---

## 数据流分析

### 1. 活性分析

#### 概述
确定哪些变量在特定点需要保存和恢复。

#### 算法
- 使用标准活性分析算法（Dragon Book, section 10.6）
- 在每个基本块计算"in"值
- 考虑异常处理块的特殊情况
- 跟踪变量的定义和使用

#### 使用场景
- 优化状态保存
- 减少内存使用
- 提高性能
- 异常处理

### 2. 类型系统

#### 概述
管理类型描述符，提供类型操作和合并功能。

#### 类型分类
- **基本类型**：int, long, float, double, boolean, byte, char, short
- **引用类型**：对象和数组
- **特殊类型**：null, undefined, return address

#### 类型合并规则
- 相同类型：返回该类型
- 数组类型：合并组件类型
- 引用类型：返回最小公共超类型
- 基本类型：必须完全匹配

#### 使用场景
- 类型推断
- 类型检查
- 类型转换
- 方法签名解析

---

## 字节码编织

### 1. ClassWeaver

#### 概述
类级别的字节码编织，负责生成状态类和修改类字节码。

#### 关键方法
- `weave()`：执行编织
- `accept(ClassVisitor cv)`：接受类访问者
- `needsWeaving()`：判断是否需要编织
- `addClassInfo(ClassInfo ci)`：添加类信息

#### 编织过程
1. 分析类的结构
2. 识别可暂停方法
3. 为每个可暂停方法生成状态类
4. 修改方法字节码
5. 生成新的字节码

#### 使用场景
- 编译时字节码编织
- 运行时字节码编织
- 状态类生成
- 类修改

### 2. MethodWeaver

#### 概述
方法级别的字节码编织，负责修改方法字节码以支持暂停和恢复。

#### 关键方法
- `accept(ClassVisitor cv)`：接受类访问者
- `weave()`：编织方法
- `createCallWeavers()`：创建调用编织器

#### 编织过程
1. 分析方法的控制流
2. 识别暂停点
3. 插入状态保存代码
4. 插入状态恢复代码
5. 修改方法签名

#### 使用场景
- 方法级别的字节码编织
- 暂停点插入
- 状态保存和恢复
- 异常处理

### 3. CallWeaver

#### 概述
方法调用编织，负责修改方法调用指令。

#### 关键方法
- `weave()`：编织方法调用
- `isPausable()`：判断是否为可暂停调用
- `insertStateSave()`：插入状态保存代码
- `insertStateRestore()`：插入状态恢复代码

#### 使用场景
- 方法调用编织
- 参数传递
- 返回值处理
- 异常传播

---

## 学习路径建议

### 1. 理解基础组件
- 从KilimContext和ClassInfo开始
- 学习TypeDesc的类型系统
- 掌握Value的SSA表示
- 理解Usage的活性分析

### 2. 学习核心分析组件
- 深入理解BasicBlock的基本块概念
- 掌握ClassFlow的类级别分析
- 学习MethodFlow的方法级别分析
- 理解Frame的激活帧管理

### 3. 研究数据流分析
- 掌握活性分析算法
- 学习类型系统
- 理解SSA（静态单赋值）
- 学习数据流分析

### 4. 理解字节码编织
- 学习ClassWeaver的类级别编织
- 掌握MethodWeaver的方法级别编织
- 理解CallWeaver的调用编织
- 学习状态类生成

### 5. 实践应用
- 编写简单的可暂停方法
- 分析字节码
- 进行字节码编织
- 测试编织后的代码

通过这样的学习路径，你能够从基础到高级，逐步掌握Kilim的字节码分析和编织技术，为接手这个项目打下良好的基础。
