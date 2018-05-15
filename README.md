# microservicebuilder.lib
A tool for enabling Jenkins builds in MicroClimate

Enable helm client tls with given certificates

Setting the secret name into HELM_SECRET environment variable enables tls options for the helm commands.

The secret should have:

> cert.pem: certificate
> key.pem: certificate key
> ca.pam: CA certificate.

Sample secret creation command:

`create secret generic <secret name> --from-file=cert-pem=.helm/cert.pem --from-file=ca-pem=.helm/ca.pem --from-file=key-pem=.helm/key.pem`
