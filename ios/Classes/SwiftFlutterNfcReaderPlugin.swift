import Flutter
import Foundation
import CoreNFC
import VYNFCKit

@available(iOS 13.0, *)
public class SwiftFlutterNfcReaderPlugin: NSObject, FlutterPlugin {
    
    fileprivate var nfcSession: NFCTagReaderSession? = nil
    fileprivate var instruction: String? = nil
    fileprivate var resulter: FlutterResult? = nil
    fileprivate var readResult: FlutterResult? = nil
    
    private var eventSink: FlutterEventSink?
    
    fileprivate let kId = "nfcId"
    fileprivate let kContent = "nfcContent"
    fileprivate let kStatus = "nfcStatus"
    fileprivate let kError = "nfcError"
    
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "flutter_nfc_reader", binaryMessenger: registrar.messenger())
        let eventChannel = FlutterEventChannel(name: "it.matteocrippa.flutternfcreader.flutter_nfc_reader", binaryMessenger: registrar.messenger())
        let instance = SwiftFlutterNfcReaderPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
        eventChannel.setStreamHandler(instance)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch(call.method) {
        case "NfcRead":
            let map = call.arguments as? Dictionary<String, String>
            instruction = map?["instruction"] ?? ""
            readResult = result
            print("read")
            activateNFC(instruction)
        case "NfcStop":
            resulter = result
            disableNFC()
        case "NfcWrite":
            var alertController = UIAlertController(title: nil, message: "IOS does not support NFC tag writing", preferredStyle: .alert)
            alertController.addAction(UIAlertAction(title: "OK", style: .default, handler: nil))
            UIApplication.shared.keyWindow?.rootViewController?.present(alertController, animated: true)
        default:
            result("iOS " + UIDevice.current.systemVersion)
        }
    }
}

// MARK: - NFC Actions
@available(iOS 13.0, *)
extension SwiftFlutterNfcReaderPlugin {
    func activateNFC(_ instruction: String?) {
        print("activate")
        
        nfcSession = NFCTagReaderSession(pollingOption: .iso18092, delegate: self)
        
        // then setup a new session
        if let instruction = instruction {
            nfcSession?.alertMessage = instruction
        }
        
        // start
        if let nfcSession = nfcSession {
            nfcSession.begin()
        }
        
    }
    
    func disableNFC() {
        nfcSession?.invalidate()
        let data = [kId: "", kContent: "", kError: "", kStatus: "stopped"]
        
        resulter?(data)
        resulter = nil
    }
    
    func sendNfcEvent(data: [String: String]){
        guard let eventSink = eventSink else {
            return
        }
        eventSink(data)
    }
}

// MARK: - NFCDelegate
@available(iOS 13.0, *)
extension SwiftFlutterNfcReaderPlugin : NFCTagReaderSessionDelegate {
    
    public func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
    }
    
    public func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        let tag = tags.first!
        session.connect(to: tag) { error in
            if let error = error {
                print("Error: ", error)
                return
            }
            guard case let .feliCa(feliCaTag) = tag else {
                session.alertMessage = "Detected not-felica card."
                return
            }

            let historyServiceCode = Data([0x09, 0x0f].reversed())
            feliCaTag.requestService(nodeCodeList: [historyServiceCode]) { nodes, error in
                if let error = error {
                    print("Error: ", error)
                    return
                }

                guard let data = nodes.first, data != Data([0xff, 0xff]) else {
                    print("Service not exists.")
                    return
                }

                let blockList = (0..<12).map {
                    Data([0x80, UInt8($0)])
                }

                // feliCaTag.readWithoutEncryption(serviceCodeList: [historyServiceCode], blockList: blockList) { status1, status2, dataList, error in          
                //     if let error = error {
                //         print("Error: ", error)
                //         return
                //     }
                //     guard status1 == 0x00, status2 == 0x00 else {
                //         print("Status flag error: ", status1, " / ", status2)
                //         return
                //     }                    
                // }
                
                let idm = feliCaTag.currentIDm.map { String(format: "%.2hhx", $0) }.joined()
                let systemCode = feliCaTag.currentSystemCode.map { String(format: "%.2hhx", $0) }.joined()

                session.alertMessage = "IDm: \(idm)\nSystem Code: \(systemCode)"

                session.invalidate()
            }
        }
    }

    public func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        session.alertMessage = "Error"
    }

}

@available(iOS 13.0, *)
extension SwiftFlutterNfcReaderPlugin: FlutterStreamHandler {
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        eventSink = nil
        return nil
    }
    
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        return nil
    }
}
