cmake_minimum_required(VERSION 3.14)
project(hutulock-client-cpp VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Options
option(BUILD_EXAMPLES "Build example programs" ON)
option(BUILD_TESTS "Build tests" ON)

# Find dependencies
find_package(Threads REQUIRED)

# Boost.Asio for networking (header-only)
find_package(Boost 1.70 REQUIRED COMPONENTS system)

# Library
add_library(hutulock-client
    src/hutulock_client.cpp
    src/connection_manager.cpp
    src/heartbeat_monitor.cpp
    src/retry_policy.cpp
    src/lock_context.cpp
    src/protocol.cpp
)

target_include_directories(hutulock-client
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(hutulock-client
    PUBLIC
        Threads::Threads
        Boost::system
)

# Examples
if(BUILD_EXAMPLES)
    add_executable(simple_lock_example examples/simple_lock_example.cpp)
    target_link_libraries(simple_lock_example hutulock-client)
    
    add_executable(enhanced_lock_example examples/enhanced_lock_example.cpp)
    target_link_libraries(enhanced_lock_example hutulock-client)
endif()

# Tests
if(BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()

# Install
install(TARGETS hutulock-client
    EXPORT hutulock-client-targets
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
    RUNTIME DESTINATION bin
)

install(DIRECTORY include/hutulock
    DESTINATION include
)
