Relance le dernier run IntelliJ sur l'instance principale (port 64342).

## Étape 1 — Port de l'instance principale

Le port MCP SSE de l'IntelliJ principal est **toujours 64342**. Ne pas le chercher, l'utiliser directement.

```
PORT=64342
```

> ⚠️ Ne pas confondre avec le sandbox `runIde` qui est sur le port 64343.

## Étape 2 — Appel SSE : execute_ide_action "Rerun"

```bash
PORT=64342

curl -s -N --max-time 20 http://127.0.0.1:$PORT/sse > /tmp/sse_run.txt &
sleep 2
SESSION=$(grep -o 'sessionId=[^"& \r]*' /tmp/sse_run.txt | head -1 | cut -d= -f2 | tr -d '\r\n')
curl -s -X POST "http://127.0.0.1:$PORT/message?sessionId=$SESSION" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"execute_ide_action","arguments":{"actionId":"Rerun"}}}'
sleep 4
kill %1 2>/dev/null
grep -v '"notifications/tools/list_changed"' /tmp/sse_run.txt | grep '"result"' | tail -3
```

## Étape 3 — Afficher le résultat

Parse la réponse et affiche le contenu de `result.content[0].text`.

- Si succès → confirmer que le run a été relancé.
- Si erreur `"No run configuration"` → indiquer qu'aucun run n'a encore été lancé dans cette session.
- Si pas de réponse → vérifier que l'IntelliJ principal est bien démarré (pas le sandbox).
