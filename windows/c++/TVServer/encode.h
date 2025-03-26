#pragma once

#include <iostream>
#include <windows.h>

std::string convertToUTF8(const std::string& input, UINT fromCodePage = 936);
std::string UTF8ToGB2312(const std::string& utf8Str);
std::string WideToMultiByte(const std::wstring& wideStr);

