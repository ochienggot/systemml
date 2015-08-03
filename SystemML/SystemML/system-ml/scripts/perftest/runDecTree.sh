#!/bin/bash
if [ "$4" == "SPARK" ]; then CMD="./sparkDML.sh "; DASH="-"; elif [ "$4" == "MR" ]; then CMD="hadoop jar SystemML.jar " ; else CMD="echo " ; fi

BASE=$3

export HADOOP_CLIENT_OPTS="-Xmx2048m -Xms2048m -Xmn256m"

echo "running decision tree"

#training
tstart=$SECONDS
${CMD} -f ../algorithms/decision-tree.dml $DASH-explain $DASH-stats $DASH-nvargs X=$1 Y=$2 fmt=csv M=${BASE}/M
ttrain=$(($SECONDS - $tstart - 3))
echo "DecisionTree train on "$1": "$ttrain >> times.txt

#predict
tstart=$SECONDS
${CMD} -f ../algorithms/decision-tree-predict.dml $DASH-explain $DASH-stats $DASH-nvargs M=${BASE}/M X=$1_test Y=$2_test P=${BASE}/P
tpredict=$(($SECONDS - $tstart - 3))
echo "DecisionTree predict on "$1": "$tpredict >> times.txt

