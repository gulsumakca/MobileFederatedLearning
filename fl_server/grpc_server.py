"""
Federated Learning gRPC Sunucusu
==================================
Kurulum : pip install -r requirements.txt
Proto   : python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. fl_service.proto
Çalıştır: python grpc_server.py

Android emülatörü → 10.0.2.2:8080
Gerçek cihaz      → <bilgisayar-IP>:8080

Endpoint'ler (gRPC):
  FLService/Fit       — yerel ağırlıkları gönder, FedAvg global model al
  FLService/GetModel  — mevcut global modeli çek
  FLService/GetStatus — sunucu durumu
"""

import os
import sys
import threading
import time
from concurrent import futures

# Windows konsolu (cp1254 vb.) → '→' gibi Unicode karakterlerde çökmesin
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except (AttributeError, ValueError):
    pass

import grpc
import numpy as np

# ── Proto stub oluşturma (ilk çalışmada otomatik) ────────────────────────────

_HERE = os.path.dirname(os.path.abspath(__file__))


# .proto dosyasından Python gRPC stub'larını ilk çalışmada otomatik üretir.
def _ensure_proto_stubs() -> None:
    pb2 = os.path.join(_HERE, "fl_service_pb2.py")
    if not os.path.exists(pb2):
        print("[Server] Proto stub'ları oluşturuluyor...")
        from grpc_tools import protoc

        ret = protoc.main(
            [
                "grpc_tools.protoc",
                f"-I{_HERE}",
                f"--python_out={_HERE}",
                f"--grpc_python_out={_HERE}",
                os.path.join(_HERE, "fl_service.proto"),
            ]
        )
        if ret != 0:
            raise RuntimeError("Proto derleme başarısız!")
        print("[Server] Proto stub'ları oluşturuldu.")


_ensure_proto_stubs()

import fl_service_pb2 as pb2  # noqa: E402
import fl_service_pb2_grpc as pb2_grpc  # noqa: E402

# ── Sabitler ──────────────────────────────────────────────────────────────────

# 10 kategori — Android tarafıyla AYNI sırada olmalı (logit indeksleri eşleşsin).
CATEGORIES = [
    "Siyaset", "Ekonomi", "Spor", "Teknoloji",
    "Bilim", "Dünya", "Doğa", "Magazin", "Yaşam", "Gündem",
]
NUM_CATEGORIES = len(CATEGORIES)
PORT = 8080

# ── FedAvg Sunucu Durumu ──────────────────────────────────────────────────────


class FedAvgState:
    """
    FedAvg algoritması (McMahan et al., 2017):
      w_global = Σ (n_k / n_total) × w_k
    Sunucu kendi geçmiş bilgisi için SERVER_PSEUDO_SAMPLES kadar örneğe sahip
    gibi davranır — tek istemcili senaryoda aşırı öğrenmeyi önler.
    """

    SERVER_PSEUDO_SAMPLES = 10

    def __init__(self) -> None:
        # _lock: aynı anda gelen isteklerde global modelin bozulmasını önler (thread güvenliği)
        self._lock = threading.Lock()
        # Başlangıç: uniform dağılım (softmax girdisi = 0) → tüm kategoriler eşit
        self.global_logits = np.zeros(NUM_CATEGORIES, dtype=np.float64)
        self.round_num = 0       # kaçıncı FL turundayız
        self.total_clients = 0   # toplam kaç istemci katkısı geldi

    # ── public API ────────────────────────────────────────────────────────────

    # İstemci ağırlıklarını FedAvg ile global modele kat, round'u artır, güncel modeli döndür.
    def fit(self, client_weights: list[float], num_samples: int) -> pb2.FitResponse:
        with self._lock:
            cw = np.clip(np.array(client_weights, dtype=np.float64), -10, 10)
            self.global_logits = self._fedavg(cw, max(num_samples, 1))
            self.round_num += 1
            self.total_clients += 1

            top_i = int(np.argmax(self.global_logits))
            probs = self._softmax(self.global_logits)
            print(
                f"[Round {self.round_num}] n={num_samples} "
                f"→ top: {CATEGORIES[top_i]} ({probs[top_i]*100:.1f}%)"
            )

            return pb2.FitResponse(
                global_weights=self.global_logits.tolist(),
                categories=CATEGORIES,
                round=self.round_num,
            )

    # Mevcut global modeli döndürür (soğuk başlangıç — yeni istemci ilk modeli çeker).
    def get_model(self) -> pb2.ModelResponse:
        with self._lock:
            return pb2.ModelResponse(
                weights=self.global_logits.tolist(),
                categories=CATEGORIES,
            )

    # Sunucu durumunu döndürür: çalışıyor mu, kaçıncı round, kaç istemci, en yüksek kategori.
    def get_status(self) -> pb2.StatusResponse:
        with self._lock:
            top_i = int(np.argmax(self.global_logits))
            return pb2.StatusResponse(
                status="running",
                round=self.round_num,
                total_clients=self.total_clients,
                top_category=CATEGORIES[top_i],
            )

    # ── private ───────────────────────────────────────────────────────────────

    # Örnek sayısına göre ağırlıklı ortalama: global ve istemci modelini birleştirir.
    # Sunucu sözde örnekleri (n_server) tek istemcinin modeli aşırı sarsmasını önler.
    def _fedavg(self, client_logits: np.ndarray, n_client: int) -> np.ndarray:
        n_server = self.SERVER_PSEUDO_SAMPLES
        n_total = n_server + n_client
        return (self.global_logits * n_server + client_logits * n_client) / n_total

    # Logit'leri olasılığa çevirir (sadece loglama/yüzde gösterimi için; toplam = 1).
    @staticmethod
    def _softmax(x: np.ndarray) -> np.ndarray:
        e = np.exp(x - x.max())
        return e / e.sum()


# ── gRPC Servis Implementasyonu ───────────────────────────────────────────────


# gRPC isteklerini karşılayan servis: gelen çağrıları FedAvgState metotlarına yönlendirir.
class FLServicer(pb2_grpc.FLServiceServicer):
    def __init__(self, state: FedAvgState) -> None:
        self._state = state

    # İstemci ağırlıklarını al → FedAvg uygula → güncel global modeli geri gönder.
    def Fit(self, request: pb2.FitRequest, context: grpc.ServicerContext) -> pb2.FitResponse:
        return self._state.fit(list(request.weights), request.num_samples)

    # Mevcut global modeli döndür (soğuk başlangıç).
    def GetModel(self, request: pb2.Empty, context: grpc.ServicerContext) -> pb2.ModelResponse:
        return self._state.get_model()

    # Sunucu durumunu döndür (sağlık kontrolü).
    def GetStatus(self, request: pb2.Empty, context: grpc.ServicerContext) -> pb2.StatusResponse:
        return self._state.get_status()


# ── Başlangıç ─────────────────────────────────────────────────────────────────


# Sunucuyu kurar ve başlatır: FedAvg durumunu, thread havuzunu ve gRPC servisini bağlar.
def serve() -> None:
    state = FedAvgState()
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    pb2_grpc.add_FLServiceServicer_to_server(FLServicer(state), server)

    # Tüm ağ arayüzlerini dinle (0.0.0.0) → emülatör ve gerçek cihaz erişebilsin.
    listen_addr = f"0.0.0.0:{PORT}"
    server.add_insecure_port(listen_addr)
    server.start()

    print("=" * 55)
    print("  Federated Learning gRPC Sunucusu")
    print("=" * 55)
    print(f"  Port    : {PORT}")
    print(f"  Kategoriler ({NUM_CATEGORIES}): {', '.join(CATEGORIES)}")
    print()
    print("  Bağlantı adresleri:")
    print("    Android emülatörü → 10.0.2.2:8080")
    print("    Gerçek cihaz      → <PC-IP>:8080")
    print()
    print("  Endpoint'ler (gRPC):")
    print("    FLService/Fit       → FL round gönder")
    print("    FLService/GetModel  → Global model al")
    print("    FLService/GetStatus → Sunucu durumu")
    print("=" * 55)

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        print("\n[Server] Durduruluyor...")
        server.stop(grace=5)


if __name__ == "__main__":
    serve()
