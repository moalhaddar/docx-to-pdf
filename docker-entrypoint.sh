#!/bin/bash

echo "Tailing supervisor log files ..."
echo "Starting docx to pdf service"
supervisord
supervisorctl tail -f docx-to-pdf stdout &
supervisorctl tail -f docx-to-pdf stderr &

function die {
	echo "Received SIGTERM/SIGINT/SIGUP signal."
	echo "Stopping service."
	exit 0
}

trap die SIGTERM SIGINT SIGHUP
sleep infinity &
wait $!