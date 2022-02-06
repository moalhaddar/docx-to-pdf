#!/bin/bash

echo "Tailing stderr log file ..."
echo "Starting docx to pdf service"

supervisorctl tail -f docx-to-pdf stdout &

function die {
	echo "Received SIGTERM/SIGINT/SIGUP signal."
	echo "Stopping service."
	exit 0
}

trap die SIGTERM SIGINT SIGHUP
sleep infinity &
wait $!