import android.app.Activity.MODE_PRIVATE
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object PathRecordsManager {
    private val pathMap = HashMap<String, Int>() // 保存路径和对应的位置
    private var isInit = false

    fun init(context: Context){
        if (isInit)
        {
            return
        }
        val sharedPreferences = context.getSharedPreferences("path_records", MODE_PRIVATE)
        val pathRecordsJson = sharedPreferences.getString("pos_records", null)

        // 加载未完成记录
        if (pathRecordsJson != null) {
            try {
                val jsonArray = JSONArray(pathRecordsJson)
                for (i in 0 until jsonArray.length()) {
                    val recordObj = jsonArray.getJSONObject(i)
                    val path = recordObj.getString("path")
                    val pos = recordObj.getInt("pos")
                    pathMap[path] = pos
                }
            } catch (e: Exception) {
                Log.e("FileListActivity", "加载记录出错", e)
            }
        }
        isInit = true
    }

    fun setPos(key:String, pos:Int) {
        pathMap[key] = pos
    }

    fun getPos(key:String): Int{
        return pathMap[key]?:0
    }

    fun save(context: Context){
        val sharedPreferences = context.getSharedPreferences("path_records", MODE_PRIVATE)
        val fileJsonArray = JSONArray()
        // 保存已完成记录
        pathMap.forEach { record ->
            val recordObj = JSONObject().apply {
                put("path", record.key)
                put("pos", record.value)
            }
            fileJsonArray.put(recordObj)
        }
        sharedPreferences.edit()
            .putString("pos_records", fileJsonArray.toString())
            .apply()
    }
}