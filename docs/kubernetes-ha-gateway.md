# HarborSync Kubernetes HA Gateway

Bu dokuman, lab ortaminda dogrulanan Kubernetes API ve uygulama giris
mimarisinin kaynak dosyalarini ve yeniden kurulum sirasini aciklar.

## Trafik Akislari

Kubernetes API:

```text
192.168.10.150:8443
  -> Keepalived VIP sahibi
  -> HAProxy
  -> master1/master2/master3:6443
```

Uygulama trafigi:

```text
192.168.10.151:80/443
  -> Keepalived VIP sahibi
  -> HAProxy
  -> worker1/worker2/worker3:30080/30443
  -> Envoy Gateway
  -> HTTPRoute
  -> Kubernetes Service
  -> uygulama Podu
```

## Kubernetes Kurulum Sirasi

Ingress worker'larini etiketleyin:

```bash
kubectl label node deniz-k8s-worker1 harborsync.io/ingress-gateway=true --overwrite
kubectl label node deniz-k8s-worker2 harborsync.io/ingress-gateway=true --overwrite
kubectl label node deniz-k8s-worker3 harborsync.io/ingress-gateway=true --overwrite
```

Gateway API'yi etkinlestirin ve controller'in hazir olmasini bekleyin:

```bash
kubectl apply -f k8s/platform/gateway/00-gateway-api-enable.yaml
kubectl get gatewayclass tigera-gateway-class
kubectl get tigerastatus gatewayapi
```

Gateway namespace'ini ve EnvoyProxy sablonunu olusturun:

```bash
kubectl apply -f k8s/platform/gateway/10-envoy-proxy.yaml
```

Lab CA ve bu CA tarafindan imzalanan sunucu sertifikasini olusturun. CA ve
sunucu private key dosyalari repository'ye eklenmemelidir:

```bash
umask 077
PKI="$HOME/harborsync-pki"
mkdir -p "$PKI"

openssl genrsa -out "$PKI/harborsync-lab-ca.key" 4096
openssl req -x509 -new -sha256 -days 3650 \
  -key "$PKI/harborsync-lab-ca.key" \
  -out "$PKI/harborsync-lab-ca.crt" \
  -subj '/CN=HarborSync Lab CA' \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -addext 'subjectKeyIdentifier=hash'

openssl genrsa -out "$PKI/app.harborsync.lab.key" 2048
openssl req -new -sha256 \
  -key "$PKI/app.harborsync.lab.key" \
  -out "$PKI/app.harborsync.lab.csr" \
  -subj '/CN=app.harborsync.lab'

printf '%s\n' \
  'authorityKeyIdentifier=keyid,issuer' \
  'basicConstraints=critical,CA:FALSE' \
  'keyUsage=critical,digitalSignature,keyEncipherment' \
  'extendedKeyUsage=serverAuth' \
  'subjectAltName=DNS:app.harborsync.lab' \
  > "$PKI/app.harborsync.lab.ext"

openssl x509 -req -sha256 -days 365 \
  -in "$PKI/app.harborsync.lab.csr" \
  -CA "$PKI/harborsync-lab-ca.crt" \
  -CAkey "$PKI/harborsync-lab-ca.key" \
  -CAcreateserial \
  -extfile "$PKI/app.harborsync.lab.ext" \
  -out "$PKI/app.harborsync.lab.crt"

openssl verify \
  -CAfile "$PKI/harborsync-lab-ca.crt" \
  "$PKI/app.harborsync.lab.crt"

kubectl create secret tls app-harborsync-lab-tls \
  -n harborsync-gateway \
  --cert="$PKI/app.harborsync.lab.crt" \
  --key="$PKI/app.harborsync.lab.key" \
  --dry-run=client -o yaml | kubectl apply -f -
```

CA private key yalnizca sertifika imzalayan guvenli sistemde tutulur. Istemcilere
sadece public `harborsync-lab-ca.crt` dagitilir. Ubuntu istemcide CA'yi sisteme
ekleyin ve lab adini Ingress VIP'ye cozdurun:

```bash
sudo install -o root -g root -m 0644 \
  "$HOME/harborsync-ca/harborsync-lab-ca.crt" \
  /usr/local/share/ca-certificates/harborsync-lab-ca.crt
sudo update-ca-certificates

printf '%s\n' '192.168.10.151 app.harborsync.lab' | \
  sudo tee -a /etc/hosts >/dev/null
```

HTTP veya HTTPS proxy ayarlari kalici olarak degistirilmez. Lab erisim testlerinde
`curl --noproxy app.harborsync.lab` kullanilarak yalnizca ilgili komut proxy disina
cikarilir. HTTPS istegi sertifika adiyla eslesmesi icin IP yerine
`app.harborsync.lab` hostname.iyle yapilir.

TLS Secret olustuktan sonra Gateway ve uygulama kaynaklarini uygulayin:

```bash
kubectl apply -f k8s/platform/gateway/20-public-gateway.yaml
kubectl apply -f k8s/platform/gateway/30-demo-web.yaml
kubectl apply -f k8s/platform/gateway/40-demo-web-route.yaml
```

## VM Yapilandirmalari

- `infra/haproxy/haproxy.cfg` uc master'da `/etc/haproxy/haproxy.cfg`
- `infra/keepalived/check_haproxy.sh` uc master'da
  `/usr/local/bin/check_haproxy.sh`
- Her master icin ilgili `infra/keepalived/masterN.conf` dosyasi
  `/etc/keepalived/keepalived.conf`

Dosyalar kurulmadan once mevcut yapilandirmalar yedeklenmeli; sonrasinda
`haproxy -c` ve `keepalived -t` ile dogrulanmalidir.

## Saglik Kontrolleri

```bash
kubectl get --raw='/readyz'
kubectl get gateway -n harborsync-gateway
kubectl get httproute -n harborsync-demo
kubectl get pods -A -o wide
```

HTTPS testi:

```bash
curl --noproxy app.harborsync.lab \
  -sS -o /dev/null \
  -w 'status=%{http_code} remote=%{remote_ip} tls_verify=%{ssl_verify_result}\n' \
  'https://app.harborsync.lab/'
```

Beklenen sonuc `status=200`, `remote=192.168.10.151` ve `tls_verify=0` olur.


## Secret Politikasi

Asagidaki veriler Git'e eklenmemelidir:

- Lab CA ve TLS sunucu private key dosyalari
- Kubernetes bootstrap token'lari
- Certificate upload key'leri
- Kubeconfig dosyalari
- Uretim VRRP parolalari

Lab ortamindaki VRRP parolasi mevcut konfigleri tekrar uretebilmek icin bu
orneklerde acik bulunur; uretimde secret yonetimi ve dosya sablonlama
kullanilmalidir.
