/**
 * Returns the cmd used to start H2O in given mode (on Hadoop or standalone). The cmd <strong>must</strong> export
 * the CLOUD_IP and CLOUT_PORT env variables (they are checked afterwards).
 * @param stageConfig stage configuration to read mode and additional information from
 * @return the cmd used to start H2O in given mode
 */
def call() {
    return new KerberosCommands();
}

class KerberosCommands {

    def getH2OStartCommand(final stageConfig) {
        switch (stageConfig.customData.mode) {
            case H2O_HADOOP_STARTUP_MODE_HADOOP:
                return getCommandHadoop(stageConfig, false)
            case H2O_HADOOP_STARTUP_MODE_HADOOP_SPNEGO:
                return getCommandHadoop(stageConfig, true)
            case H2O_HADOOP_STARTUP_MODE_STEAM_DRIVER:
                return getCommandHadoop(stageConfig, false, true, true)
            case H2O_HADOOP_STARTUP_MODE_STEAM_MAPPER:
                return getCommandHadoop(stageConfig, false, true, false)
            case H2O_HADOOP_STARTUP_MODE_SPARKLING:
                return getCommandHadoop(stageConfig, false, false, false, true)
            case H2O_HADOOP_STARTUP_MODE_STEAM_SPARKLING:
                return getCommandHadoop(stageConfig, false, true, false, true)
            case H2O_HADOOP_STARTUP_MODE_STANDALONE:
                return getCommandStandalone(stageConfig)
            default:
                error("Startup mode ${stageConfig.customData.mode} for H2O with Hadoop is not supported")
        }
    }

    private GString getCommandHadoop(
            final stageConfig, final boolean spnegoAuth,
            final boolean impersonate = false, final boolean hdpCp = true,
            final boolean prepareToken = false
    ) {
        def defaultPort = 54321
        def loginArgs
        def loginEnvs
        if (spnegoAuth) {
            loginArgs = """-spnego_login -user_name ${stageConfig.customData.kerberosUserName} \\
                -login_conf ${stageConfig.customData.spnegoConfigPath} \\
                -spnego_properties ${stageConfig.customData.spnegoPropertiesPath}"""
            loginEnvs = """export KERB_PRINCIPAL=${stageConfig.customData.kerberosPrincipal}"""
        } else {
            loginArgs = "-kerberos_login -login_conf ${stageConfig.customData.kerberosConfigPath}"
            loginEnvs = ""
        }
        def hadoopClasspath = ""
        if (hdpCp) {
            hadoopClasspath = "export HADOOP_CLASSPATH=\$(cat /opt/hive-jdbc-cp)"
        }
        def impersonationArgs = ""
        if (impersonate) {
            impersonationArgs = "-principal steam/localhost@H2O.AI -keytab /etc/hadoop/conf/steam.keytab -run_as_user jenkins"
        }
        def tokenPreparation = ""
        def usePreparedToken = ""
        def h2odriverJar = "h2o-hadoop-*/h2o-${stageConfig.customData.distribution}${stageConfig.customData.version}-assembly/build/libs/h2odriver.jar"
        if (prepareToken) {
            tokenPreparation = """
            HADOOP_CLASSPATH=\$(cat /opt/hive-jdbc-cp) hadoop jar h2o-hive/build/libs/h2o-hive.jar water.hive.GenerateHiveToken \\
                -tokenFile hive.token ${impersonationArgs} \\
                -hivePrincipal hive/localhost@H2O.AI -hiveHost localhost:10000
            """
            usePreparedToken = "-hiveToken \$(cat hive.token)"
        }
        return """
            rm -fv h2o_one_node h2odriver.log
            if [ "\$HIVE_DIST_ENABLED" == "true" ]; then
                # hive 2+, regular refresh is enough
                REFRESH_TOKENS_CONF="--refreshTokens"
                CHECK_TOKEN_REFRESH_MAKE_TARGET=test-kerberos-verify-token-refresh
            else if [ "${impersonationArgs}" != "" ]; then
                # use keytab to refresh tokens on hive 1.x
                REFRESH_TOKENS_CONF="--refreshTokens --refreshTokensMode keytab"
                CHECK_TOKEN_REFRESH_MAKE_TARGET=test-kerberos-verify-token-refresh
            else
                REFRESH_TOKENS_CONF="" # disable refresh for hive 1.x without impersonation
            fi
            ${hadoopClasspath}
            ${tokenPreparation}
            hadoop jar ${h2odriverJar} \\
                -n 1 -mapperXmx 2g -baseport 54445 ${impersonationArgs} -timeout 300 \\
                -hivePrincipal hive/localhost@H2O.AI -hiveHost localhost:10000 \$REFRESH_TOKENS_CONF ${usePreparedToken} \\
                -jks mykeystore.jks \\
                -notify h2o_one_node -ea -proxy -port ${defaultPort} \\
                -jks mykeystore.jks \\
                ${loginArgs} \\
                > h2odriver.log 2>&1 &
            for i in \$(seq 20); do
              if [ -f 'h2o_one_node' ]; then
                echo "H2O started on \$(cat h2o_one_node)"
                break
              fi
              echo "Waiting for H2O to come up (\$i)..."
              sleep 15
            done
            if [ ! -f 'h2o_one_node' ]; then
              echo 'H2O failed to start!'
              cat h2odriver.log
              exit 1
            fi
            ${loginEnvs}
            export KRB_USE_TOKEN=true
            export CLOUD_IP=localhost
            export CLOUD_PORT=${defaultPort}
        """
    }

    private GString getCommandStandalone(final stageConfig) {
        def defaultPort = 54321
        return """
            java -cp build/h2o.jar:\$(cat /opt/hive-jdbc-cp) water.H2OApp \\
                -port ${defaultPort} -ip \$(hostname --ip-address) -name \$(date +%s) \\
                -jks mykeystore.jks \\
                -spnego_login -user_name ${stageConfig.customData.kerberosUserName} \\
                -login_conf ${stageConfig.customData.spnegoConfigPath} \\
                -spnego_properties ${stageConfig.customData.spnegoPropertiesPath} \\
                > standalone_h2o.log 2>&1 & sleep 15
            export KERB_PRINCIPAL=${stageConfig.customData.kerberosPrincipal}
            export CLOUD_IP=\$(hostname --ip-address)
            export CLOUD_PORT=${defaultPort}
        """
    }

    private GString getExtraMakeTarget() {
        return "\$CHECK_TOKEN_REFRESH_MAKE_TARGET"
    }
}

return this
