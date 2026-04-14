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

## Étape 3 — Afficher le résultat du Rerun

Parse la réponse et affiche le contenu de `result.content[0].text`.

- Si succès → confirmer que le run a été relancé, puis passer à l'étape 4.
- Si erreur `"No run configuration"` → indiquer qu'aucun run n'a encore été lancé dans cette session.
- Si pas de réponse → vérifier que l'IntelliJ principal est bien démarré (pas le sandbox).

## Étape 4 — Attendre 30s puis vérifier que le sandbox est prêt

Après avoir relancé le run (qui redémarre le sandbox `runIde`), attendre 30 secondes puis vérifier 10 fois toutes les 5 secondes que le sandbox (port 64343) a bien chargé le plugin.

```bash
echo "Sandbox relancé — attente 30s avant de vérifier..."
sleep 30

for i in $(seq 1 10); do
  PORT_SANDBOX=64343

  # Ouvrir une connexion SSE sur le sandbox (20s max pour laisser le temps à la réponse d'arriver)
  curl -s -N --max-time 20 http://127.0.0.1:$PORT_SANDBOX/sse > /tmp/sse_check.txt 2>/dev/null &
  SSE_PID=$!
  sleep 2

  SESSION_CHECK=$(grep -o 'sessionId=[^"& \r]*' /tmp/sse_check.txt | head -1 | cut -d= -f2 | tr -d '\r\n')

  if [ -z "$SESSION_CHECK" ]; then
    echo "$(date +%H:%M:%S) [${i}/10] — sandbox pas encore démarré"
    kill $SSE_PID 2>/dev/null
    sleep 5
    continue
  fi

  # Appeler tools/list pour vérifier que le plugin est chargé
  curl -s -X POST "http://127.0.0.1:$PORT_SANDBOX/message?sessionId=$SESSION_CHECK" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' 2>/dev/null
  sleep 3
  kill $SSE_PID 2>/dev/null

  PLUGIN_CHECK=$(grep -v '"notifications/tools/list_changed"' /tmp/sse_check.txt | grep '"result"' | grep -o 'run_inspections' | head -1)

  if [ -n "$PLUGIN_CHECK" ]; then
    echo "$(date +%H:%M:%S) ✅ Sandbox prêt — plugin MCP Companion chargé !"
    break
  else
    echo "$(date +%H:%M:%S) [${i}/10] — MCP actif mais plugin pas encore chargé"
    sleep 5
  fi
done
```

## Étape 5 — Résumé final

- Si ✅ → indiquer que le sandbox est opérationnel.
- Si toujours pas prêt après 10 tentatives → demander à l'utilisateur de vérifier que `runIde` a bien redémarré et que le MCP Server est activé dans Settings → Tools → MCP Server → Enable MCP Server.
