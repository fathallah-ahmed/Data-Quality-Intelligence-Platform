from setuptools import setup, find_packages

setup(
    name='data_quality_wevioo',
    version='0.0.0',
    packages=find_packages(exclude=("tests", "tests.*")),
    include_package_data=True,
)
