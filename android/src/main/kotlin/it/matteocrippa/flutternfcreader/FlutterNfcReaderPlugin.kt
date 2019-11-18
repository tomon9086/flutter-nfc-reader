package it.matteocrippa.flutternfcreader

import android.Manifest
import android.content.Context
//import android.content.Intent
import android.nfc.*
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Handler
import android.util.SparseArray

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
//import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.TextView
import android.widget.Toast
//import kotlinx.android.synthetic.main.activity_main.*
import java.io.ByteArrayOutputStream

import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.nio.charset.Charset
import android.os.Looper
import java.io.IOException

const val PERMISSION_NFC = 1007

class FlutterNfcReaderPlugin(val registrar: Registrar) : MethodCallHandler, EventChannel.StreamHandler, NfcAdapter.ReaderCallback {

    private val activity = registrar.activity()

    private var nfcAdapter: NfcAdapter? = null
    private var nfcManager: NfcManager? = null
//
    private var kId = "nfcId"
    private var kContent = "nfcContent"
    private var kError = "nfcError"
    private var kStatus = "nfcStatus"
    private var kWrite = ""
    private var kPath = ""
    private var readResult: Result? = null
//    private var writeResult: Result? = null
    private var tag: Tag? = null
    private var eventChannel: EventChannel.EventSink? = null;

    private var NFC_TYPES = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_BARCODE or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V

    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar): Unit {
            val messenger = registrar.messenger()
            val channel = MethodChannel(messenger, "flutter_nfc_reader")
            val eventChannel = EventChannel(messenger, "it.matteocrippa.flutternfcreader.flutter_nfc_reader")
            val plugin = FlutterNfcReaderPlugin(registrar)
            channel.setMethodCallHandler(plugin)
            eventChannel.setStreamHandler(plugin)
        }
    }

//     fun onCreate(savedInstanceState: Bundle?) {
//        var textView = findViewById<TextView>(R.id.textView1)
//        textView.setMovementMethod(ScrollingMovementMethod.getInstance())
//    }

//     fun onNewIntent(intent: Intent?) {
//        if(readResult != null) {
//            return
//        }
//
//        val tag = intent?.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
//
//        if (tag != null){
//            val result = read(tag)
//            val ndef = Ndef.get(tag)
//            ndef?.connect()
//            val ndefMessage = ndef?.ndefMessage ?: ndef?.cachedNdefMessage
//            val message = ndefMessage?.toByteArray()
//                    ?.toString(Charset.forName("UTF-8")) ?: ""
//            //val id = tag?.id?.toString(Charset.forName("ISO-8859-1")) ?: ""
//            val id = bytesToHexString(tag?.id) ?: ""
//            ndef?.close()
//            val data = mapOf(kId to id, kContent to message, kError to "", kStatus to "reading")
//            val mainHandler = Handler(Looper.getMainLooper())
//            mainHandler.post {
//
////                Toast.makeText(this, "tag: '$tag', id: '${tag.id.joinToString(" ")}'", Toast.LENGTH_SHORT).show()
////                var i = 0
////                var text = ""
////                result?.blocks?.forEach {
////                    var rireki = Rireki.parse(it)
////                    Log.d("FelicaRireki", rireki.toString())
////                    var outputText = "${text}${rireki.toString()}"
////                    text = outputText
////                    i++
////                }
//
//                readResult?.success(data)
//                readResult = null
//            }
//        } else {
//            Log.d("FALSE", "$intent")
//                            // convert tag to NDEF tag
//            val ndef = Ndef.get(tag)
//            ndef?.connect()
//            val ndefMessage = ndef?.ndefMessage ?: ndef?.cachedNdefMessage
//            val message = ndefMessage?.toByteArray()
//                    ?.toString(Charset.forName("UTF-8")) ?: ""
//            val id = bytesToHexString(tag?.id) ?: ""
//            ndef?.close()
//            val data = mapOf(kId to id, kContent to message, kError to "", kStatus to "reading")
//            val mainHandler = Handler(Looper.getMainLooper())
//            mainHandler.post {
//                eventChannel?.success(data);
//            }
//        }
//    }

    init {
        nfcManager = activity.getSystemService(Context.NFC_SERVICE) as? NfcManager
        nfcAdapter = nfcManager?.defaultAdapter

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.requestPermissions(
                    arrayOf(Manifest.permission.NFC),
                    PERMISSION_NFC
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            nfcAdapter?.enableReaderMode(activity, this, NFC_TYPES, null)
        }
    }

    private fun writeMessageToTag(nfcMessage: NdefMessage, tag: Tag?): Boolean {

        try {
            val nDefTag = Ndef.get(tag)

            nDefTag?.let {
                it.connect()
                if (it.maxSize < nfcMessage.toByteArray().size) {
                    //Message to large to write to NFC tag
                    return false
                }
                return if (it.isWritable) {
                    it.writeNdefMessage(nfcMessage)
                    it.close()
                    //Message is written to tag
                    true
                } else {
                    //NFC tag is read-only
                    false
                }
            }

            val nDefFormatableTag = NdefFormatable.get(tag)

            nDefFormatableTag?.let {
                return try {
                    it.connect()
                    it.format(nfcMessage)
                    it.close()
                    //The data is written to the tag
                    true
                } catch (e: IOException) {
                    //Failed to format tag
                    false
                }
            }
            //NDEF is not supported
            return false

        } catch (e: Exception) {
            //Write operation has failed
        }
        return false
    }

    fun createNFCMessage(payload: String?, intent: Intent?): Boolean {

        val pathPrefix = "it.matteocrippa.flutternfcreader"
        val nfcRecord = NdefRecord(NdefRecord.TNF_EXTERNAL_TYPE, pathPrefix.toByteArray(), ByteArray(0), (payload as String).toByteArray())
        val nfcMessage = NdefMessage(arrayOf(nfcRecord))
        intent?.let {
            val tag = it.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            return writeMessageToTag(nfcMessage, tag)
        }
        return false
    }

    override fun onMethodCall(call: MethodCall, result: Result): Unit {

        if (nfcAdapter?.isEnabled != true) {
            result.error("404", "NFC Hardware not found", null)
            return
        }

        when (call.method) {
            "NfcStop" -> {
                readResult = null
//                writeResult = null
            }

            "NfcRead" -> {
                readResult = result
            }

//            "NfcWrite" -> {
//                writeResult = result
//                kWrite = call.argument("label")!!
//                kPath = call.argument("path")!!
//                if (this.tag != null) {
//                    writeTag()
//                }
//            }

            else -> {
                result.notImplemented()
            }
        }
    }



    // EventChannel.StreamHandler methods
    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventChannel = events;
    }

    override fun onCancel(arguments: Any?) {
        eventChannel =  null;
    }



    private fun stopNFC() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            nfcAdapter?.disableReaderMode(activity)
        }
    }


//
//    private fun readTag() {
//        if (readResult != null) {
//            // convert tag to NDEF tag
//            val ndef = Ndef.get(tag)
//            ndef?.connect()
//            val ndefMessage = ndef?.ndefMessage ?: ndef?.cachedNdefMessage
//            val message = ndefMessage?.toByteArray()
//                    ?.toString(Charset.forName("UTF-8")) ?: ""
//            //val id = tag?.id?.toString(Charset.forName("ISO-8859-1")) ?: ""
//            val id = bytesToHexString(tag?.id) ?: ""
//            ndef?.close()
//            val data = mapOf(kId to id, kContent to message, kError to "", kStatus to "reading")
//            val mainHandler = Handler(Looper.getMainLooper())
//            mainHandler.post {
//                readResult?.success(data)
//                readResult = null
//            }
//        } else {
//            // convert tag to NDEF tag
//            val ndef = Ndef.get(tag)
//            ndef?.connect()
//            val ndefMessage = ndef?.ndefMessage ?: ndef?.cachedNdefMessage
//            val message = ndefMessage?.toByteArray()
//                    ?.toString(Charset.forName("UTF-8")) ?: ""
//            val id = bytesToHexString(tag?.id) ?: ""
//            ndef?.close()
//            val data = mapOf(kId to id, kContent to message, kError to "", kStatus to "reading")
//            val mainHandler = Handler(Looper.getMainLooper())
//            mainHandler.post {
//                eventChannel?.success(data);
//            }
//        }
//    }

    fun read(tag: Tag): ReadWithoutEncryptionResponse? {
        val nfc = NfcF.get(tag)
        Log.d("tag.getId", "${tag.id}")
        try {
            nfc.connect()
            val result = read(nfc)
            Log.d("FelicaSample", "read without encryption: $result")

            if (result != null) {
                result.blocks.forEachIndexed { i, bytes -> Log.d("Felica sample", "block[$i] '${bytes.joinToString(" ")}' " ) }
            }
            nfc.close()

            return  result
        } catch (e: Exception) {
            Log.e("FelicaSample", "cannot read nfc, $e")
            e.printStackTrace()
            if (nfc.isConnected) {
                nfc.close()
            }
            return  null
        }
    }

    private fun read(nfc: NfcF): ReadWithoutEncryptionResponse {
        // System 1のシステムコード -> 0x0003 (SUICA/PASUMO などの鉄道系)
        val targetSystemCode = byteArrayOf(0x00.toByte(), 0x03.toByte())

        // [1]: Polling コマンドオブジェクトのリクエストパケットを nfc に送信し、Polling の結果を受け取る。
        val pollingCommand = PollingCommand(targetSystemCode)
        val pollingRequest = pollingCommand.requestPacket()
        val rawPollingResponse = nfc.transceive(pollingRequest)
        val pollingResponse = PollingResponse(rawPollingResponse)

        // Polling で得られた IDm を取得する。
        val targetIDm = pollingResponse.IDm()

        // 実行したいサービスコードを生成する。
//        val serviceCode = byteArrayOf(0x00.toByte(), 0x8B.toByte())
        val serviceCode = byteArrayOf(0x0f.toByte(), 0x09.toByte())

        // [2]: Request Service コマンドオブジェクトのリクエストパケットを nfc に送信し、Request Service の結果を受け取る。
        val requestServiceCommand = RequestServiceCommand(targetIDm, serviceCode)
        val requestServiceRequest = requestServiceCommand.requestPacket()
        val rawRequestServiceResponse = nfc.transceive(requestServiceRequest)
        val requestServiceResponse = RequestServiceResponse(rawRequestServiceResponse)

        // [3]: Read Without Encryption コマンドオブジェクトのリクエストパケットを nfc に送信し、Read Without Encryption の結果を受け取る。
//        val readWithoutEncryptionCommand = ReadWithoutEncryptionCommand(IDm = targetIDm, serviceCode = serviceCode, blocks = arrayOf(BlockListElement2(BlockListElement.AccessMode.toNotParseService, 0, 0)))
        // 履歴情報はブロック数20のデータが必要なので、20ブロックのByteArrayデータを作成する
//        val readWithoutEncryptionCommand = ReadWithoutEncryptionCommand(IDm = targetIDm, serviceCode = serviceCode, blocks = Array(10, {i -> BlockListElement2(BlockListElement.AccessMode.toNotParseService, 0, i) }))
//        val readWithoutEncryptionRequest = readWithoutEncryptionCommand.requestPacket()
//        val rawReadWithoutEncryptionResponse = nfc.transceive(readWithoutEncryptionRequest)
        val req = readWthOutEncryptionCommand(targetIDm, 10)
        val rawReadWithoutEncryptionResponse = nfc.transceive(req)

        Log.d("TAG", "res:"+toHex(rawReadWithoutEncryptionResponse));
        val readWithouEncryptionResponse = ReadWithoutEncryptionResponse(rawReadWithoutEncryptionResponse)

        return readWithouEncryptionResponse
    }

    private fun readWthOutEncryptionCommand(idm: ByteArray, size: Int): ByteArray {
        val bout = ByteArrayOutputStream(100)

        bout.write(0)           // データ長バイトのダミー
        bout.write(0x06)        // Felicaコマンド「Read Without Encryption」
        bout.write(idm)         // カードID 8byte
        bout.write(1)           // サービスコードリストの長さ(以下２バイトがこの数分繰り返す)
        bout.write(0x0f)        // 履歴のサービスコード下位バイト
        bout.write(0x09)        // 履歴のサービスコード上位バイト
        bout.write(size)        // ブロック数
        for (i in 0 until size) {
            bout.write(0x80)    // ブロックエレメント上位バイト 「Felicaユーザマニュアル抜粋」の4.3項参照
            bout.write(i)       // ブロック番号
        }

        val msg = bout.toByteArray()
        msg[0] = msg.size.toByte() // 先頭１バイトはデータ長
        return msg
    }

    private fun toHex(id: ByteArray): String {
        val sbuf = StringBuilder()
        for (i in id.indices) {
            var hex = "0" + Integer.toString(id[i].toInt() and 0x0ff, 16)
            if (hex.length > 2)
                hex = hex.substring(1, 3)
            sbuf.append(" $i:$hex")
        }
        return sbuf.toString()
    }

    // handle discovered NDEF Tags
    override fun onTagDiscovered(tag: Tag?) {
        this.tag = tag
//        readTag()
        Handler(Looper.getMainLooper()).postDelayed({
            this.tag = null
        }, 2000)
    }

    private fun bytesToHexString(src: ByteArray?): String? {
        val stringBuilder = StringBuilder("0x")
        if (src == null || src.isEmpty()) {
            return null
        }

        val buffer = CharArray(2)
        for (i in src.indices) {
            buffer[0] = Character.forDigit(src[i].toInt().ushr(4).and(0x0F), 16)
            buffer[1] = Character.forDigit(src[i].toInt().and(0x0F), 16)
            stringBuilder.append(buffer)
        }

        return stringBuilder.toString()
    }
}

abstract class BlockListElement(val accessMode: AccessMode, val serviceCodeIndex: Int, val number: Int) {
    /** アクセスモードの列挙体 */
    enum class AccessMode(val value: Int) {
        toNotParseService(0), toParseService(1)
    }

    /** ブロックリストエレメントの最初の byte を取得する。 */
    fun firstByte(): Byte {
        // 1byte
        //    [0] エレメントのサイズ (1bit) 0: 2byteエレメント, 1: 3byteエレメント
        //    [1] アクセスモード (1bit)
        //    [2..7] エレメントが対象とするサービスコードのサービスコードリスト内の番号 (6bit)
        //    x 0 0 0 0 0 0 0 <- エレメントのサイズ
        //    0 x 0 0 0 0 0 0 <- アクセスモード
        //  & 0 0 x x x x x x <- サービスコードリスト内の順番
        return ((0 shl 7) and (accessMode.value shl 6) and (serviceCodeIndex shl 3)).toByte()
    }
    abstract fun toByteArray(): ByteArray
    abstract fun size(): Int
}

class BlockListElement2(accessMode: AccessMode, serviceCodeIndex: Int, number: Int) : BlockListElement(accessMode, serviceCodeIndex, number) {
    /** エレメントのパケットを取得する。 */
    override fun toByteArray(): ByteArray {
        return ByteArray(2).apply {
            this[0] = firstByte()
            this[1] = number.toByte()
        }
    }

    /** サイズを取得する。 */
    override fun size(): Int {
        return 2
    }
}

class BlockListElementRireki(accessMode: AccessMode, serviceCodeIndex: Int, number: Int) : BlockListElement(accessMode, serviceCodeIndex, number) {
    /** エレメントのパケットを取得する。 */
    override fun toByteArray(): ByteArray {
        return ByteArray(20).apply {
            this[0] = firstByte()
            this[19] = number.toByte()
        }
    }
    override fun size(): Int {

        /** サイズを取得する。 */
        return 20
    }
}

interface NfcCommand {
    val commandCode: Byte
    fun requestPacket(): ByteArray
}

abstract class NfcResponse {
    companion object {
        var response: ByteArray = ByteArray(0)
    }
    constructor(sendResponse: ByteArray) {
        response = sendResponse
    }
    abstract fun responseSize(): Int
    abstract fun responseCode(): Byte
    abstract  fun IDm(): ByteArray
}

data class PollingCommand(private val systemCode: ByteArray, private val request: Request = PollingCommand.Request.systemCode) : NfcCommand {
    /** Polling コマンドで取得する情報の列挙体。 */
    enum class Request(val value: Byte) {
        none(0x00), systemCode(0x01), communicationAbility(0x02)
    }

    /** Polling コマンドのコマンドコードは 0x00 */
    override val commandCode: Byte
        get() = 0x00

    /** タイムスロットは 0F 固定とする（ほんとはもっとちゃんと指定できる。仕様編参照） */
    val timeSlot: Byte
        get() = 0x0f

    /** リクエストコードのバイト値 */
    val requestCode: Byte
        get() = request.value

    /** リクエストパケットを取得する。 */
    override fun requestPacket(): ByteArray {
        return ByteArray(6).apply {
            var i = 0
            this[i++] = 0x06              // [0] 最初はリクエストパケットのサイズが入る。6byte固定。
            this[i++] = commandCode       // [1] コマンドコードが入る。
            this[i++] = systemCode[0]     // [2] システムコードの先頭byteが入る。
            this[i++] = systemCode[1]     // [3] システムコードの末尾byteが入る。
            this[i++] = requestCode       // [4] リクエストコードが入る。
            this[i++] = timeSlot          // [5] タイムスロットが入る。
        }
    }
}

class PollingResponse(response: ByteArray) : NfcResponse(response) {
    /** レスポンスのサイズを取得する。 */
    override fun responseSize(): Int {
        return response[0].toInt()
    }

    /** レスポンスコードの取得をする。 */
    override fun responseCode(): Byte {
        return response[1]
    }

    /** IDm を取得する。 */
    override fun IDm(): ByteArray {
        return response.copyOfRange(2, 10)
    }
}

data class ReadWithoutEncryptionCommand(private val IDm: ByteArray, private val serviceList: Array<ByteArray>, val blocks: Array<BlockListElement>) : NfcCommand {
    /** サービスコードが 1種類の場合のためのセカンダリコンストラクタ。 */
    constructor(IDm: ByteArray, serviceCode: ByteArray, blocks: Array<BlockListElement>): this(IDm, arrayOf(serviceCode), blocks)

    /** Read Without Encryption コマンドのコマンドコードは 0x06 */
    override val commandCode: Byte
        get() = 0x06.toByte()

    /** サービスの数 */
    private val numberOfServices = serviceList.size

    /** ブロックの数 */
    private val numberOfBlocks: Int = blocks.size

    /** ブロックリスト全体の byte 数 */
    private val blockSize = blocks.map { it.size() }.reduce { a, b -> a + b }

    /** パケットサイズ */
    private val packetSize = 13 + (numberOfServices * 2) + blockSize

    /** リクエストパケットを取得する。 */
    override fun requestPacket(): ByteArray {
        return ByteArray(packetSize).apply {
            var i = 0
            this[i++] = packetSize.toByte()       // [0] 最初はリクエストパケットのサイズが入る。
            this[i++] = commandCode               // [1] コマンドコードが入る。
            IDm.forEach { this[i++] = it }        // [2..10] IDｍ (8byte) が入る。
            this[i++] = numberOfServices.toByte() // [11] サービスコードリストの数が入る。
            serviceList.forEach {
                it.forEachIndexed { index, byte ->  // [12..] サービスコード (2byte) が入る。
                    if ((index % 2) == 0) {           //        サービスコードはリトルエンディアンなので
                        this[i + 1] = byte              //        2byte が反転するように格納する。
                    } else {
                        this[i - 1] = byte
                    }
                    i++
                }
            }
            this[i++] = numberOfBlocks.toByte()    // [12 + 2 * numberOfServices + 1] ブロックリストの数が入る。
            blocks.forEach { it.toByteArray().forEach { this[i++] = it } }
            // [...] ブロックリストエレメントのパケットが順繰り入る。
        }
    }
}

class ReadWithoutEncryptionResponse : NfcResponse {
    val blocks: Array<ByteArray>
    constructor(response: ByteArray): super(response) {
        blocks = blocks()
    }


    override fun responseSize(): Int {
        return response[0].toInt()
    }

    override fun responseCode(): Byte {
        return response[1]
    }

    override fun IDm(): ByteArray {
        return response.copyOfRange(2,10)
    }

    fun statusFlag1(): Byte {
        return response[10]
    }

    fun statusFlag2(): Byte {
        return response[11]
    }

    fun numberOfBlocks(): Int {
        return response[12].toInt()
    }

    private fun blocks(): Array<ByteArray> {
        var i = 0
        var results = arrayOf<ByteArray>()
        var raw = response.copyOfRange(13, response.size)
        while ( i < numberOfBlocks()) {
            var addResult = raw.copyOfRange(i * 16, i * 16 + 16)
            results += addResult

            Log.d("Felica.parse", "${addResult[0].toInt()}")
            i++
        }
        return  results
    }
}

data class RequestServiceCommand(private val IDm: ByteArray, private val nodeCodeList: Array<ByteArray>) : NfcCommand {
    /** ノードコードが１つの場合のためのセカンダリコンストラクタ。 */
    constructor(IDm: ByteArray, nodeCode: ByteArray): this(IDm, arrayOf(nodeCode))

    /** Request Service コマンドのコマンドコードは 0x02 */
    override val commandCode: Byte
        get() = 0x2

    /** ノードコードの件数 */
    private val numberOfNodes = (nodeCodeList.size).toByte()

    /** パケットのサイズ */
    private val packetSize = (11 + nodeCodeList.size * 2)

    /** リクエストパケットを取得する。 */
    override fun requestPacket(): ByteArray {
        return ByteArray(packetSize).apply {
            var i = 0
            this[i++] = packetSize.toByte()       // [0] 最初はリクエストパケットのサイズが入る。
            this[i++] = commandCode               // [1] コマンドコードが入る。
            IDm.forEach { this[i++] = it }        // [2..10] IDm (8byte) が入る。
            this[i++] = numberOfNodes             // [11] ノードの数が入る。
            nodeCodeList.forEach {
                it.forEachIndexed { index, byte ->
                    if ((index % 2) == 0) {           // [12..] ノードコード (2byte) が入る。
                        this[i + 1] = byte              //        ノードコードはリトルエンディアンなので
                    } else {                          //        2byte が反転するように格納する。
                        this[i - 1] = byte
                    }
                    i++
                }
            }
        }
    }
}

class RequestServiceResponse(response: ByteArray) : NfcResponse(response) {
    /** ノードコードに対応するノードの鍵バージョンモデル */
    data class NodeKeyVersion(private val values: ByteArray) {
        val value: ByteArray
            get() {
                return ByteArray(2).apply {
                    this[0] = values[1]
                    this[1] = values[0]
                }
            }
    }

    /** レスポンスのサイズを取得する。 */
    override fun responseSize(): Int {
        return response[0].toInt()
    }

    /** レスポンスコードを取得する。 */
    override fun responseCode(): Byte {
        return response[1]
    }

    /** IDm を取得する。 */
    override fun IDm(): ByteArray {
        return response.copyOfRange(2, 10)
    }

    /** ノードの数を取得する。 */
    fun numberOfNodes(): Int {
        return response[11].toInt()
    }

    /** ノードの鍵バージョンの配列を取得する。 */
    fun nodeKeyVersions(): Array<NodeKeyVersion> {
        var i = 0
        val results = arrayListOf<NodeKeyVersion>()
        val rawNodeKeyVersions = response.copyOfRange(12, response.size)
        while (i < numberOfNodes()) {
            results += NodeKeyVersion(ByteArray(2).apply {
                this[0] = rawNodeKeyVersions[i * 2]
                this[1] = rawNodeKeyVersions[(i * 2) + 1]
            })
        }
        return results.toTypedArray()
    }
}

class Rireki {
    var termId: Int = 0
    var procId: Int = 0
    var year = 0
    var month = 0
    var day = 0
    var remain = 0

    private fun init(res: ByteArray) {
        var termId = res[0].toInt() //0: 端末種
        if (termId < 0) {
            termId += 256
        }
        this.termId = termId
        var procId = res[1].toInt()
        this.procId = procId
        var mixInt = toInt(res, 4, 5)
        this.year = mixInt shr 9 and 0x07f
        this.month = mixInt shr 5 and 0x00f
        this.day = mixInt and 0x01f
        this.remain  = toInt(res, 11,10)
    }

    private fun toInt(res: ByteArray, vararg idx: Int): Int {
        var num = 0
        for (i in idx.indices) {
            num = num shl 8
            num += res[idx[i]].toInt() and 0x0ff
        }
        return num
    }

    override fun toString(): String {
        return ("機器種別：${TERM_MAP.get(termId)}\n" +
                "利用種別:${PROC_MAP.get(procId)}\n" +
                "日付:${this.year}/${this.month}/${this.day} \n" +
                "残額：${this.remain} \n\n") }

    companion object {

        fun parse(res: ByteArray): Rireki {
            val self = Rireki()
            self.init(res)
            return self
        }

        val TERM_MAP = SparseArray<String>()
        val PROC_MAP = SparseArray<String>()

        init {
            TERM_MAP.put(3, "精算機")
            TERM_MAP.put(4, "携帯型端末")
            TERM_MAP.put(5, "車載端末")
            TERM_MAP.put(7, "券売機")
            TERM_MAP.put(8, "券売機")
            TERM_MAP.put(9, "入金機")
            TERM_MAP.put(18, "券売機")
            TERM_MAP.put(20, "券売機等")
            TERM_MAP.put(21, "券売機等")
            TERM_MAP.put(22, "改札機")
            TERM_MAP.put(23, "簡易改札機")
            TERM_MAP.put(24, "窓口端末")
            TERM_MAP.put(25, "窓口端末")
            TERM_MAP.put(26, "改札端末")
            TERM_MAP.put(27, "携帯電話")
            TERM_MAP.put(28, "乗継精算機")
            TERM_MAP.put(29, "連絡改札機")
            TERM_MAP.put(31, "簡易入金機")
            TERM_MAP.put(70, "VIEW ALTTE")
            TERM_MAP.put(72, "VIEW ALTTE")
            TERM_MAP.put(199, "物販端末")
            TERM_MAP.put(200, "自販機")

            PROC_MAP.put(1, "運賃支払(改札出場)")
            PROC_MAP.put(2, "チャージ")
            PROC_MAP.put(3, "券購(磁気券購入)")
            PROC_MAP.put(4, "精算")
            PROC_MAP.put(5, "精算 (入場精算)")
            PROC_MAP.put(6, "窓出 (改札窓口処理)")
            PROC_MAP.put(7, "新規 (新規発行)")
            PROC_MAP.put(8, "控除 (窓口控除)")
            PROC_MAP.put(13, "バス (PiTaPa系)")
            PROC_MAP.put(15, "バス (IruCa系)")
            PROC_MAP.put(17, "再発 (再発行処理)")
            PROC_MAP.put(19, "支払 (新幹線利用)")
            PROC_MAP.put(20, "入A (入場時オートチャージ)")
            PROC_MAP.put(21, "出A (出場時オートチャージ)")
            PROC_MAP.put(31, "入金 (バスチャージ)")
            PROC_MAP.put(35, "券購 (バス路面電車企画券購入)")
            PROC_MAP.put(70, "物販")
            PROC_MAP.put(72, "特典 (特典チャージ)")
            PROC_MAP.put(73, "入金 (レジ入金)")
            PROC_MAP.put(74, "物販取消")
            PROC_MAP.put(75, "入物 (入場物販)")
            PROC_MAP.put(198, "物現 (現金併用物販)")
            PROC_MAP.put(203, "入物 (入場現金併用物販)")
            PROC_MAP.put(132, "精算 (他社精算)")
            PROC_MAP.put(133, "精算 (他社入場精算)")
        }
    }
}