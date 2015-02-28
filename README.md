# Chibi SMSC

Chibi's SMSC

## Configuration

See [.chibi_smsc_configuration](https://github.com/dwilkie/chibi-smsc/blob/master/.chibi_smsc_configuration)

## Building

```
mvn package
```

## Testing

### Locally

#### Enqueue a job

From the terminal run:

```
bundle exec foreman run -e .chibi_smsc_configuration ruby enqueue_mt.rb
```

#### Start the SMPP Client

From another terminal run:

```
bundle exec foreman run smpp_client
```

#### Start the Test SMPP Server

This will boot the test SMPP Server by [Cloudhopper](https://github.com/twitter/cloudhopper-smpp)

From yet another terminal run:

```
cd /path/to/cloudhopper
make server-echo
```

#### Start a local Sidekiq Worker

This will start Sidekiq which will process the required jobs in Ruby

From yet another terminal run:

```
bundle exec foreman start sidekiq_worker -e .chibi_smsc_configuration
```

### Remotely

From the development machine first charge `REDISTOGO_URL` in [.chibi_smsc_configuration](https://github.com/dwilkie/chibi-smsc/blob/master/.chibi_smsc_configuration) with the remote `REDISTOGO_URL`.

Then run:

```
bundle exec foreman run ruby enqueue_mt.rb -e .chibi_smsc_configuration
```

This will enqueue a job on the `REDISTOGO` server and should be picked up by the java process on the remote server.

## Deployment

```
bundle exec cap production deploy
```

## Troubleshooting Using Wireshark

### Capture outgoing packets

```
sudo tcpdump -i eth0 -nnvvS dst host <public-ip-of-vpn-host-not-internal-ip> -w "`date +'%Y-%m-%d'`_description.cap"
```

### Capture incoming packets

```
sudo tcpdump -i eth0 -nnvvS dst host 174.129.212.2 -w "`date +'%Y-%m-%d'`_description.cap"
```

### Download the packets locally

```
sftp -i ~/.ssh/aws/dwilkie.pem ubuntu@nuntium.chibitxt.me:output.cap .
```

### Decrypting outgoing packets using Wireshark

From the server run the following:

```
sudo ip xfrm state
```

This will output something like:

```
src <src-address> dst <dst-address>
  proto esp spi <spi> reqid 16397 mode tunnel
  replay-window 32 flag af-unspec
  auth-trunc hmac(md5) <Authentication-Key> 96
  enc cbc(des3_ede) <Encryption-Key>
  encap type espinudp sport 4500 dport 4500 addr 0.0.0.0
```

In WireShark Enable ESP decryption

```
Edit -> Preferences -> Protocols -> ESP -> Attempt to detect/decode encrypted ESP payloads
```

Then under

```
ESP SAs: -> Edit -> New
```

Fill in the following fields with the output from above:

* Source Address
* Destination Address
* SPI
* Encryption
* Encryption Key
* Authentication
* Authentication Key

From the example output above `Encryption` is `TripleDES-CBC` (which comes from `enc cbc(des3_ede)`) and `Authentication` is `HMAC-MD5-96` (which comes from `auth-trunc hmac(md5) <Authentication-Key> 96`)
