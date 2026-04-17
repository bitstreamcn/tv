
#include "filelist.h"
#include "encode.h"

using json = nlohmann::json;
namespace fs = std::filesystem;

// 获取所有驱动器
json getDrives() {
    json drives;
    drives["status"] = "success";
    drives["current_path"] = "drives";
    drives["items"] = json::array();

    char drivesStr[26 * 4];
    GetLogicalDriveStringsA(sizeof(drivesStr), drivesStr);
    for (char* drive = drivesStr; *drive; drive += strlen(drive) + 1) {
        json driveInfo;
        driveInfo["name"] = convertToUTF8(std::string("驱动器(") + drive + ")");
        driveInfo["type"] = "drive";
        driveInfo["path"] = convertToUTF8(drive);
        driveInfo["size"] = 0L;
        drives["items"].push_back(driveInfo);
    }
    return drives;
}

// 辅助函数：从字符串中提取数字部分
int extractNumber(const std::string& s, size_t& pos) {
    int num = 0;
    while (pos < s.length()) {
        unsigned char c = static_cast<unsigned char>(s[pos]);
        if (c <= 127 && std::isdigit(c)) {
            num = num * 10 + (c - '0');
            pos++;
        }
        else {
            break;
        }
    }
    return num;
}

// 自然排序比较函数
bool naturalSort(const fs::path& a, const fs::path& b) {
    std::string strA = a.stem().string();
    std::string strB = b.stem().string();
    size_t i = 0, j = 0;
    std::locale loc;

    while (i < strA.length() && j < strB.length()) {
        unsigned char charA = static_cast<unsigned char>(strA[i]);
        unsigned char charB = static_cast<unsigned char>(strB[j]);

        bool isDigitA = charA <= 127 && std::isdigit(charA);
        bool isDigitB = charB <= 127 && std::isdigit(charB);

        if (isDigitA && isDigitB) {
            int numA = extractNumber(strA, i);
            int numB = extractNumber(strB, j);
            if (numA != numB) {
                return numA < numB;
            }
        }
        else if (!isDigitA && !isDigitB) {
            if (std::tolower(charA, loc) != std::tolower(charB, loc)) {
                return std::tolower(charA, loc) < std::tolower(charB, loc);
            }
            i++;
            j++;
        }
        else {
            return isDigitA < isDigitB;
        }
    }
    return strA.length() < strB.length();
}


std::string filename(const fs::path& path)
{
    const std::filesystem::path &fullPath = path; // full path
    std::string fullUtf8Path = fullPath.u8string();

    // 找到最后一个分隔符，提取文件名
    size_t pos = fullUtf8Path.find_last_of("/\\");
    std::string filename = (pos == std::string::npos)
        ? fullUtf8Path
        : fullUtf8Path.substr(pos + 1);
    return filename;
}

// 获取目录内容
json getDirectoryContent(const std::string& path) {

    std::string _path = path;
    if (_path.back() == '\0')
    {
        _path.pop_back();
    }

    if (_path == "drives") {
        return getDrives();
    }

    json result;
    try {
        bool hasEx = false;
        result["status"] = "success";

        std::filesystem::path p = std::filesystem::u8path(path);  // 明确是 UTF-8
        result["current_path"] = p.u8string();
        result["items"] = json::array();
        std::vector<fs::path> dirs;
        std::vector<fs::path> files;
        
        for (const auto& entry : fs::directory_iterator(p)) {
            if (entry.is_directory())
            {
                dirs.push_back(entry.path());
            }
            else {
                files.push_back(entry.path());
            }
        }
        // 对文件列表进行自然排序
        std::sort(files.begin(), files.end(), naturalSort);
        for (const auto& file : dirs) {
            //std::cout << file.filename() << std::endl;
            json item;
            item["name"] = filename(file);
            item["type"] = "directory";
            item["path"] = file.u8string();
            item["size"] = 0L;
            result["items"].push_back(item);
        }
        // 输出排序后的文件列表
        for (const auto& file : files) {
            //std::cout << file.filename() << std::endl;
            json item;
            item["name"] = filename(file);
            item["type"] = "file";
            item["path"] = file.u8string();
            item["size"] = std::filesystem::file_size(file);
            result["items"].push_back(item);
        }
    }
    catch (const std::exception& e) {
        result["status"] = "error";
        result["message"] = convertToUTF8(e.what());
    }
    return result;
}

