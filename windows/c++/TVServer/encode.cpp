#include "encode.h"


std::string convertToUTF8(const std::string& input, UINT fromCodePage) {

    int wideLength = MultiByteToWideChar(fromCodePage, 0, input.c_str(), -1, nullptr, 0);
    if (wideLength == 0) {
        std::cerr << "MultiByteToWideChar failed." << std::endl;
        return "";
    }

    std::wstring wideString(wideLength, L'\0');
    MultiByteToWideChar(fromCodePage, 0, input.c_str(), -1, &wideString[0], wideLength);

    int utf8Length = WideCharToMultiByte(CP_UTF8, 0, wideString.c_str(), -1, nullptr, 0, nullptr, nullptr);
    if (utf8Length == 0) {
        std::cerr << "WideCharToMultiByte failed." << std::endl;
        return "";
    }

    std::string utf8String(utf8Length, '\0');
    WideCharToMultiByte(CP_UTF8, 0, wideString.c_str(), -1, &utf8String[0], utf8Length, nullptr, nullptr);
    if (utf8String.back() == '\0')
    {
        utf8String.pop_back();
    }
    return utf8String;
}

// ½« UTF-8 ×Ö·û´®×ª»»Îª GB2312 ×Ö·û´®
std::string UTF8ToGB2312(const std::string& utf8Str) {
    // µÚÒ»²½£º½« UTF-8 ×ª»»Îª¿í×Ö·û×Ö·û´®
    int wideLength = MultiByteToWideChar(CP_UTF8, 0, utf8Str.c_str(), -1, nullptr, 0);
    if (wideLength == 0) {
        std::cerr << "MultiByteToWideChar failed: " << GetLastError() << std::endl;
        return "";
    }
    std::wstring wideStr(wideLength, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8Str.c_str(), -1, &wideStr[0], wideLength);

    // µÚ¶þ²½£º½«¿í×Ö·û×Ö·û´®×ª»»Îª GB2312 ×Ö·û´®
    int gb2312Length = WideCharToMultiByte(936, 0, wideStr.c_str(), -1, nullptr, 0, nullptr, nullptr);
    if (gb2312Length == 0) {
        std::cerr << "WideCharToMultiByte failed: " << GetLastError() << std::endl;
        return "";
    }
    std::string gb2312Str(gb2312Length, '\0');
    WideCharToMultiByte(936, 0, wideStr.c_str(), -1, &gb2312Str[0], gb2312Length, nullptr, nullptr);
    if (gb2312Str.back() == '\0')
    {
        gb2312Str.pop_back();
    }
    return gb2312Str;
}

// ½«¿í×Ö·û×Ö·û´®×ª»»Îª¶à×Ö½Ú×Ö·û´®
std::string WideToMultiByte(const std::wstring& wideStr) {
    int len = WideCharToMultiByte(CP_ACP, 0, wideStr.c_str(), -1, nullptr, 0, nullptr, nullptr);
    if (len == 0) {
        return "";
    }
    std::string multiByteStr(len, 0);
    WideCharToMultiByte(CP_ACP, 0, wideStr.c_str(), -1, &multiByteStr[0], len, nullptr, nullptr);
    return multiByteStr;
}


