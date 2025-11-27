cmake_minimum_required(VERSION 3.22.1)

project("cameraapp")

add_library(cameraapp-native SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(cameraapp-native ${log-lib})
