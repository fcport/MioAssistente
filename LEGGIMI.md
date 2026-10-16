# Mio Assistente — "Ehi Hermes" sul telefono Android

Di' "Ehi Hermes" → il telefono ascolta la domanda → la manda a Hermes sul tuo PC → ti legge la risposta.

## Parte 1 — Sul PC: accendi il "ponte" di Hermes
1. Apri il file `~/.hermes/.env` (cartella `.hermes` nella tua home) e aggiungi:
   ```
   API_SERVER_ENABLED=true
   API_SERVER_KEY=inventa-una-password-lunga
   API_SERVER_HOST=0.0.0.0
   ```
   `0.0.0.0` serve perché il telefono possa raggiungere il PC dalla Wi-Fi di casa.
2. Avvia Hermes con:
   ```
   hermes gateway
   ```
   Deve comparire una riga tipo `API server listening on ... :8642`.
3. Trova l'indirizzo del PC:
   - Windows: apri il Prompt dei comandi, scrivi `ipconfig`, guarda "Indirizzo IPv4" (es. 192.168.1.50)
   - Mac/Linux: nel terminale scrivi `ip a` oppure `ifconfig` e cerca un indirizzo 192.168.x.x
4. Prova dal telefono: apri il browser e vai su `http://192.168.1.50:8642/v1/models`
   (con il tuo indirizzo). Se risponde qualcosa (anche un errore di autorizzazione), il collegamento funziona.
   Se non si apre proprio, il firewall del PC sta bloccando la porta 8642: va consentita.

⚠️ Il ponte di Hermes dà accesso completo al tuo PC (anche ai comandi). Usa una password lunga
e tienilo solo sulla rete di casa, senza aprire la porta su Internet.

## Parte 2 — Nell'app
Non devi modificare nessun file: apri l'app sul telefono e inserisci
- la frase di attivazione (es. "ehi hermes")
- l'indirizzo del PC (quello Tailscale, tipo 100.84.12.7)
- la password di Hermes (la stessa di API_SERVER_KEY)
poi premi **Salva e avvia assistente**.

## Parte 3 — Installa sul telefono
1. Apri la cartella `MioAssistente` con **Android Studio**.
2. Collega il telefono (Debug USB attivo) e premi ▶ Run.
3. Nell'app premi **Avvia assistente** e concedi microfono e notifiche.
4. Al primo avvio scarica il modello vocale (~48 MB, meglio sotto Wi-Fi): vedi la percentuale nella notifica.

## Consigli
- **Batteria**: Impostazioni → App → Mio Assistente → Batteria → **Senza restrizioni**.
- **Tasto accensione** (facoltativo): Impostazioni → App → App predefinite → **App assistente digitale**.
- Il PC deve essere acceso con `hermes gateway` avviato. Fuori casa serve qualcosa come Tailscale.
- La notifica fissa è obbligatoria su Android per ascoltare in background.
