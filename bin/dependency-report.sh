#!/bin/bash


DIR=`dirname $0`
OUTPUT=target/dependency-report

rm -rf $OUTPUT
mkdir -p $OUTPUT
MODULES="bom parent commons core tools query query-dsl remote-query/remote-query-client remote-query/remote-query-server lucene lucene/lucene-directory lucene/lucene-v3 persistence persistence/jdbc persistence/remote client/hotrod-client cli/cli-server cli/cli-client cdi/extension compatibility52x compatibility52x/adaptor52x"

for MODULE in $MODULES; do
	echo Processing $MODULE
	DEPS=`echo $MODULE|tr / -`
	mead dependency:list -DincludeParents=true -pl $MODULE | cut -c 11- | egrep ":(compile|pom)" | grep -v "^Some problems" | sort > $OUTPUT/module-$DEPS.txt
done

cat $OUTPUT/module-* | sort | uniq > $OUTPUT/global.txt

echo "Infinispan Dependency Report - Global" > $OUTPUT/report.txt
echo "-------------------------------------" >> $OUTPUT/report.txt
echo >> $OUTPUT/report.txt
cat $OUTPUT/global.txt >> $OUTPUT/report.txt
echo >> $OUTPUT/report.txt

for MODULE in $MODULES; do
	DEPS=`echo $MODULE|tr / -`
	echo $MODULE >> $OUTPUT/report.txt
	echo "-------------------------------------" >> $OUTPUT/report.txt
	echo >> $OUTPUT/report.txt
	cat $OUTPUT/module-$DEPS.txt >> $OUTPUT/report.txt
	echo >> $OUTPUT/report.txt
done

cat $OUTPUT/global.txt | cut -d: -f 1,2,4 | sort > $OUTPUT/jdg-all-artifacts.txt

while read IN; do
	GAV=(${IN//:/ })
	echo -n ${GAV[0]} |tr . / >> $OUTPUT/jdg-artifact-paths.txt
	echo /${GAV[1]}/${GAV[2]} >> $OUTPUT/jdg-artifact-paths.txt
done < $OUTPUT/jdg-all-artifacts.txt
cat $DIR/jboss-eap-*-artifacts.txt | sort > $OUTPUT/eap-artifacts.txt

comm -1 -3 $OUTPUT/eap-artifacts.txt $OUTPUT/jdg-artifact-paths.txt |grep redhat- > $OUTPUT/jdg-artifacts.txt

