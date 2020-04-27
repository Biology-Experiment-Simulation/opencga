#!/bin/bash
cd $(dirname "$0")

set -e

rgName=$(cat azuredeploy.parameters.private.json | jq -r '.parameters.rgPrefix.value')

if [[ "$#" -ne 2 ]]; then
  echo "Usage: createsp.sh <subscriptionName> <servicePrincipalName>"
  echo "Recommended servicePrincipalName: ${rgName}-aks"
  exit 1
fi

subscriptionName=$1
spname=$2

az account set --subscription $subscriptionName
spdetails=$(az ad sp create-for-rbac --years 5 -n $spname --skip-assignment)
sleep 10
aksServicePrincipalAppId=$(echo $spdetails | jq -r '.appId')
aksServicePrincipalClientSecret=$(echo $spdetails | jq -r '.password')
aksServicePrincipalObjectId=$(az ad sp show --id $aksServicePrincipalAppId --query "objectId" -o tsv)

echo "----------------------------------------------------------------------"
echo "Service Principal AppId        : ${aksServicePrincipalAppId}"
echo "Service Principal ClientSecret : ${aksServicePrincipalClientSecret}"
echo "Service Principal ObjectId     : ${aksServicePrincipalObjectId}"
echo "----------------------------------------------------------------------"
echo ""
echo "To assign roles (to be run separately if the user does not have enough permissions):"
echo "   ./roleAssignment/opencga_role_assignments.sh $subscriptionName $rgName $aksServicePrincipalObjectId"
echo "To deploy: "
echo "   ./deploy.sh $subscriptionName $aksServicePrincipalAppId $aksServicePrincipalClientSecret $aksServicePrincipalObjectId"
