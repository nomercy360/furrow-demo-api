# Secret workflow: gitops/secrets.yaml is an age-encrypted values file — git
# only ever holds ciphertext. The private key lives locally (and in the
# cluster, for furrow); the public key is committed as gitops/age.pub.
AGE_KEY    ?= $(HOME)/.config/furrow-demo/age.key
RECIPIENTS  = gitops/age.pub
SECRETS     = gitops/secrets.yaml
EDITOR     ?= vi

.PHONY: secret-show secret-edit secret-push test run

## Decrypt and print the current sealed values.
secret-show:
	age -d -i $(AGE_KEY) $(SECRETS)

## Decrypt -> $$EDITOR -> re-seal. Plaintext only ever touches a temp file.
secret-edit:
	@tmp=$$(mktemp /tmp/secrets.XXXXXX.yaml); \
	age -d -i $(AGE_KEY) $(SECRETS) > $$tmp; \
	$(EDITOR) $$tmp; \
	age -e -a -R $(RECIPIENTS) -o $(SECRETS) $$tmp; \
	rm -f $$tmp; \
	echo "re-sealed $(SECRETS) — review with 'make secret-show', then 'make secret-push'"

## Commit + push the re-sealed file; furrow picks it up on its next poll and the
## checksum/secret annotation rolls the pods.
secret-push:
	git add $(SECRETS)
	git commit -m "chore(secrets): update sealed values"
	git push origin main

test:
	mvn -B verify

run:
	docker build -t furrow-demo-api . && docker run --rm -p 8080:8080 furrow-demo-api
