cmake_minimum_required(VERSION 3.18)
project(isostream C)
add_library(isostream SHARED iso_stream.c)
target_compile_options(isostream PRIVATE -O2 -Wall)
