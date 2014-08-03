#!/usr/bin/env bash

PREFIX=target/htsjdk
VERSION=1.118

mvn clean package -DskipTests

echo "deploy ${PREFIX}-${VERSION}-SNAPSHOT by:"
echo "   mvn deploy:deploy-file -DrepositoryId=sonatype-nexus-snapshots -Dfile=${PREFIX}-${VERSION}.jar -Durl=https://oss.sonatype.org/content/repositories/snapshots -Dsources=${PREFIX}-${VERSION}-sources.jar -Djavadoc=${PREFIX}-${VERSION}-javadoc.jar -DpomFile=pom.xml -Dversion=${VERSION}-SNAPSHOT"
echo ""
echo "sign and deploy ${PREFIX}-${VERSION} to staging by:"
echo "   mvn gpg:sign-and-deploy-file -DrepositoryId=sonatype-nexus-staging -Dfile=${PREFIX}-${VERSION}.jar -Durl=https://oss.sonatype.org/service/local/staging/deploy/maven2/ -Dsources=${PREFIX}-${VERSION}-sources.jar -Djavadoc=${PREFIX}-${VERSION}-javadoc.jar -DpomFile=pom.xml -Dversion=${VERSION} -Dgpg.keyname=_GPG_KEY_ID_"

