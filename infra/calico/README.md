# Calico CNI (v3.32.0)

HarborSync cluster'i CNI olarak Calico + Tigera Operator kullanir.
Operator versiyonu: `v1.42.0`, Calico versiyonu: `v3.32.0`.

## Kurulum sirasi

kubeadm ile control-plane ayaga kalktiktan **sonra**, worker'lar join olmadan
once (veya hemen sonra) CNI kurulur:

```bash
# 1) Tigera Operator + CRD'ler (upstream stock manifest, versiyona sabitlenmis)
kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.32.0/manifests/tigera-operator.yaml

# 2) HarborSync'e ozel ag konfigurasyonu (bu klasordeki dosyalar)
kubectl create -f installation.yaml
kubectl create -f apiserver.yaml

# 3) Tum node'lar Ready olana kadar bekleyin
kubectl get tigerastatus
kubectl get nodes -o wide
```

> Offline/hava boslugu olan ortamlar icin `tigera-operator.yaml`,
> `projectcalico-crds.yaml` dosyalarini yukaridaki URL'den indirip yaninizda
> tasiyabilirsiniz; imaj'lar `quay.io/calico/*` ve `quay.io/tigera/operator`
> reposundandir.

## Onemli notlar

- **Pod CIDR uyumu:** `installation.yaml` icindeki `ipPools.cidr: 172.30.0.0/16`,
  `infra/kubeadm/kubeadm-init.example.yaml` icindeki `networking.podSubnet`
  degeriyle **birebir ayni** olmalidir. Aksi halde pod agi calismaz.
- **Arayuz adi:** `installation.yaml` -> `nodeAddressAutodetectionV4.interface: "^ens18$"`
  bu ortamdaki NIC adidir. Kendi node'larinizdaki arayuz adiyla degistirin.
