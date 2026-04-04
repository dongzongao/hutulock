# 构建说明

## 系统要求

- C++17 编译器（GCC 7+, Clang 5+, MSVC 2017+）
- CMake 3.14 或更高版本
- Boost 1.70 或更高版本

## 依赖安装

### macOS

```bash
# 使用 Homebrew
brew install cmake boost
```

### Ubuntu/Debian

```bash
sudo apt-get update
sudo apt-get install -y cmake g++ libboost-system-dev libboost-dev
```

### CentOS/RHEL

```bash
sudo yum install -y cmake gcc-c++ boost-devel
```

### Windows

1. 安装 [CMake](https://cmake.org/download/)
2. 安装 [Visual Studio](https://visualstudio.microsoft.com/)
3. 下载并安装 [Boost](https://www.boost.org/users/download/)

## 编译步骤

### 1. 创建构建目录

```bash
cd hutulock-client-cpp
mkdir build
cd build
```

### 2. 配置 CMake

```bash
# 默认配置
cmake ..

# 或指定 Boost 路径（如果未自动找到）
cmake -DBOOST_ROOT=/path/to/boost ..

# Release 模式
cmake -DCMAKE_BUILD_TYPE=Release ..

# 不编译示例
cmake -DBUILD_EXAMPLES=OFF ..

# 不编译测试
cmake -DBUILD_TESTS=OFF ..
```

### 3. 编译

```bash
# Linux/macOS
make -j$(nproc)

# Windows (Visual Studio)
cmake --build . --config Release
```

### 4. 运行测试（可选）

```bash
# 运行所有测试
ctest

# 或直接运行测试可执行文件
./hutulock_tests

# 详细输出
ctest --verbose
```

### 5. 运行示例

```bash
# 简单示例
./simple_lock_example

# 增强示例
./enhanced_lock_example
```

## 安装

```bash
sudo make install
```

安装后，可以在其他项目中使用：

```cmake
find_package(hutulock-client REQUIRED)
target_link_libraries(your_app hutulock-client)
```

## 常见问题

### 找不到 Boost

如果 CMake 找不到 Boost，可以手动指定路径：

```bash
cmake -DBOOST_ROOT=/usr/local/boost_1_70_0 ..
```

### 编译错误：C++17 不支持

确保使用支持 C++17 的编译器：

```bash
# 指定编译器
cmake -DCMAKE_CXX_COMPILER=g++-9 ..
```

### 链接错误

如果遇到 Boost 链接错误，尝试：

```bash
cmake -DBoost_USE_STATIC_LIBS=ON ..
```

## 交叉编译

### ARM Linux

```bash
cmake -DCMAKE_TOOLCHAIN_FILE=arm-linux-gnueabihf.cmake ..
```

### Android

```bash
cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-21 ..
```

## 性能优化

### 启用 LTO（链接时优化）

```bash
cmake -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON ..
```

### 启用原生优化

```bash
cmake -DCMAKE_CXX_FLAGS="-march=native" ..
```

## 调试

### Debug 模式

```bash
cmake -DCMAKE_BUILD_TYPE=Debug ..
make
```

### 使用 AddressSanitizer

```bash
cmake -DCMAKE_CXX_FLAGS="-fsanitize=address -g" ..
make
```

### 使用 Valgrind

```bash
valgrind --leak-check=full ./simple_lock_example
```

## 生成文档

如果安装了 Doxygen：

```bash
doxygen Doxyfile
```

文档将生成在 `docs/html/` 目录。
