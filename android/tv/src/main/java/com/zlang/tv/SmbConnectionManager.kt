package com.zlang.tv

import jcifs.CIFSContext
import jcifs.Configuration
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import org.json.JSONArray
import java.util.Properties

object SmbConnectionManager {
    private val contextMap = HashMap<String, CIFSContext>() // 保存路径和对应的位置

    fun init(serverlist : JSONArray?)
    {
        for (entry in contextMap.entries) {
            println("Key: ${entry.key}, Value: ${entry.value}")
            entry.value.close()
        }
        contextMap.clear()
        val list = serverlist
        if (null == list)
        {
            return
        }
        for (i in 0 until (list.length()?:0)) {
            val fileObj = list.getJSONObject(i)
            val name = fileObj.getString("name")
            val ip = fileObj.getString("ip")
            val user = fileObj.getString("user")
            val password = fileObj.getString("password")
            val props = Properties().apply {
                // 基础配置
                put("jcifs.smb.client.enableSMB2", "true")
                put("jcifs.smb.client.disableSMB1", "true")

                // 连接超时（毫秒）
                put("jcifs.smb.client.connTimeout", "30000")

                // 响应超时（毫秒）
                put("jcifs.smb.client.responseTimeout", "60000")

                // 关闭空闲连接（秒）
                put("jcifs.smb.client.connectionTimeout", "60")

                // 最大重试次数
                put("jcifs.smb.client.soTimeout", "5000")

                // 禁用签名验证（根据服务器配置调整）
                setProperty("jcifs.smb.client.disableSMB2SignatureVerify", "true")

                // 认证配置（可选，也可以在URL中包含）
                put("jcifs.smb.client.username", user)
                put("jcifs.smb.client.password", password)
            }
            val config: Configuration = PropertyConfiguration(props)
            contextMap[ip] = BaseContext (config)
        }
    }

    fun getContext(ip: String) : CIFSContext?{
        return contextMap[ip]
    }
}

