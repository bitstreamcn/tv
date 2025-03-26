#pragma once

#include "include/nlohmann/json.hpp" //  π”√ nlohmann/json ø‚

#include <iostream>
#include <winsock2.h>
#include <windows.h>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <filesystem>
#include <sstream>
#include <fstream>
#include <chrono>
#include <cstdlib>



nlohmann::json getDrives();
nlohmann::json getDirectoryContent(const std::string& path);


