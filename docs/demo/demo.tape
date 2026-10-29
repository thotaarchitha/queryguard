# Terminal part of the README demo, recorded with VHS against the running compose stack:
#
#   docker build -t qg-vhs docs/demo
#   docker run --rm --network queryguard_default -v "$PWD/docs:/vhs" qg-vhs demo/demo.tape
#
# Real commands, real output; nothing is faked or edited afterwards.

Output demo.gif

Set Shell bash
Set FontSize 17
Set Width 1180
Set Height 640
Set Padding 22
Set TypingSpeed 28ms
Set Theme { "background": "#14161A", "foreground": "#E8E4DA", "cursor": "#E0A526", "black": "#14161A", "red": "#E0492B", "green": "#4E8C82", "yellow": "#E0A526", "blue": "#7A9CC6", "magenta": "#B48EAD", "cyan": "#8FBCBB", "white": "#E8E4DA", "brightBlack": "#8A877F", "brightRed": "#F08A72", "brightGreen": "#9ED0C6", "brightYellow": "#F0C66A", "brightBlue": "#9DB8DC", "brightMagenta": "#C9A6C2", "brightCyan": "#A9D2D1", "brightWhite": "#FFFFFF", "selection": "#3A3E46" }

Hide
Type "export PS1='$ ' PGHOST=proxy PGPORT=6432 PGDATABASE=shop PSQL_PAGER= ; clear"
Enter
Sleep 500ms
Show

Type "# bob is an app user. A DELETE with no WHERE never reaches Postgres:"
Enter
Type "PGUSER=bob PGPASSWORD=bob psql -c 'DELETE FROM orders'"
Enter
Sleep 2.2s

Type "# alice is an analyst. Renaming the column doesn't get around masking:"
Enter
Type "PGUSER=alice PGPASSWORD=alice psql -c 'SELECT id, email AS e, phone, card_last4 FROM customers WHERE id IN (7, 42)'"
Enter
Sleep 2.8s

Type "# ...and neither does wrapping it in a function:"
Enter
Type "PGUSER=alice PGPASSWORD=alice psql -c 'SELECT upper(email) FROM customers WHERE id = 42'"
Enter
Sleep 2.4s

Type "# Expensive analyst query: EXPLAIN prices it, QueryGuard refuses it:"
Enter
Type "PGUSER=alice PGPASSWORD=alice psql -c 'SELECT customer_id, sum(total_cents) FROM orders GROUP BY 1 ORDER BY 2 DESC'"
Enter
Sleep 3.5s
